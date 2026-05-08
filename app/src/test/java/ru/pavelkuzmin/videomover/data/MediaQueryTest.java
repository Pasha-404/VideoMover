package ru.pavelkuzmin.videomover.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MediaQueryTest {
    @Test
    public void normalizeRelPath_addsTrailingSlashAndRemovesLeadingSlash() {
        assertEquals("DCIM/Camera/", MediaQuery.normalizeRelPath("/DCIM/Camera"));
        assertEquals("DCIM/Camera/", MediaQuery.normalizeRelPath("DCIM/Camera/"));
    }

    @Test
    public void looksLikeCameraPath_acceptsKnownCameraFolders() {
        assertTrue(MediaQuery.looksLikeCameraPath("DCIM/DJI Album/"));
        assertTrue(MediaQuery.looksLikeCameraPath("Pictures/OpenCamera/"));
        assertFalse(MediaQuery.looksLikeCameraPath("Download/Movies/"));
    }

    @Test
    public void pickBestCameraRelPath_prefersDcimCamera() {
        String best = MediaQuery.pickBestCameraRelPath(Arrays.asList(
                "DCIM/DJI Album/",
                "DCIM/Camera/",
                "DCIM/OpenCamera/"));

        assertEquals("DCIM/Camera/", best);
    }

    @Test
    public void pickBestCameraRelPath_handlesEmptyList() {
        assertNull(MediaQuery.pickBestCameraRelPath(Collections.emptyList()));
    }
}
