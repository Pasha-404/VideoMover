package ru.pavelkuzmin.videomover.domain;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

import ru.pavelkuzmin.videomover.util.HashUtil;

public class FileCopier {

    public static class Result {
        public final boolean ok;
        public final String finalName;
        public final long bytes;
        public final String sha256;
        public final String error;

        public Result(boolean ok, String finalName, long bytes, String sha256, String error) {
            this.ok = ok; this.finalName = finalName; this.bytes = bytes; this.sha256 = sha256; this.error = error;
        }
    }

    /** Копирует srcUri → destDir, создавая временный "<name>.partial", затем переименовывает. */
    public static Result copyWithSha256(Context ctx, Uri srcUri, String displayName, long expectedSize, DocumentFile destDir) {
        try {
            // Разрулим коллизию имён для финального файла (finalName)
            String base = displayName;
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0 && dot < base.length()-1) {
                ext = base.substring(dot);
                base = base.substring(0, dot);
            }
            String finalName = ensureUniqueName(destDir, base, ext);

            // Создаём временный .partial
            String tempName = finalName + ".partial";
            DocumentFile tempFile = destDir.createFile("video/*", tempName);
            if (tempFile == null) return new Result(false, null, 0, null, "Не удалось создать временный файл");
            Uri tempUri = tempFile.getUri();

            ContentResolver cr = ctx.getContentResolver();
            byte[] buf = new byte[1024 * 1024]; // 1MB буфер
            long written = 0;

            MessageDigest md = MessageDigest.getInstance("SHA-256");

            try (InputStream in = cr.openInputStream(srcUri);
                 OutputStream out = cr.openOutputStream(tempUri)) {
                if (in == null || out == null) return new Result(false, null, 0, null, "Нет доступа к потоку");

                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                    out.write(buf, 0, read);
                    written += read;
                }
                out.flush();
            }

            if (expectedSize > 0 && written != expectedSize) {
                // Размер не совпал — удаляем temp и выходим
                DocumentsContract.deleteDocument(cr, tempUri);
                return new Result(false, null, written, null, "Размер не совпал");
            }

            String hash = HashUtil.toHex(md.digest());

            // Переименовываем .partial → финальное имя
            Uri renamed = DocumentsContract.renameDocument(cr, tempUri, finalName);
            if (renamed == null) {
                DocumentsContract.deleteDocument(cr, tempUri);
                return new Result(false, null, written, hash, "Не удалось переименовать файл");
            }

            return new Result(true, finalName, written, hash, null);

        } catch (SecurityException se) {
            return new Result(false, null, 0, null, "SecurityException: " + se.getMessage());
        } catch (Exception e) {
            return new Result(false, null, 0, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String ensureUniqueName(DocumentFile dir, String base, String ext) {
        String candidate = base + ext;
        int n = 1;
        while (dir.findFile(candidate) != null) {
            candidate = base + " (" + n + ")" + ext;
            n++;
        }
        return candidate;
    }
}
