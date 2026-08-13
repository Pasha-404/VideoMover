package ru.pavelkuzmin.videomover.data;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class TransferJournalInstrumentedTest {
    @Test
    public void verifiedItemBecomesDeletionCandidateOnlyWithItsHash() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TransferJournal journal = TransferJournal.get(context);
        Uri destination = Uri.parse("content://test/tree/usb");
        Uri source = Uri.parse("content://media/external/video/media/42");
        long operationId = journal.beginOperation(destination, true);
        journal.addItems(operationId, Collections.singletonList(
                new MediaQuery.VideoItem(source, "camera.mp4", 42L, "DCIM/Camera/")));
        journal.markItemVerified(operationId, 0, false, "abc", Uri.parse("content://test/document/camera"), "camera.mp4");

        assertEquals(1, journal.getDeleteCandidates(operationId).size());
        assertEquals("abc", journal.getDeleteCandidates(operationId).get(0).sourceHash);
        journal.finishOperation(operationId, TransferJournal.OP_DONE);
    }
}
