package org.octolab.qcom32ims;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Read-only, zero-PII status ContentProvider ({@code content://org.octolab.qcom32ims.status})
 * hosted inside the {@code :volteims} process.
 *
 * Allows the companion app ({@code org.octolab.qcom32volte}) to inspect live
 * {@code rild_ims0}, {@code QMI_IMSA}, and Android {@code ImsService} registration health
 * directly on the phone screen while unplugged (e.g. during 3-carrier SIM swaps)
 * without requiring Magisk {@code su} or any {@code <uses-permission>}.
 */
public final class VolteStatusProvider extends ContentProvider {
    public static final String AUTHORITY = "org.octolab.qcom32ims.status";
    public static final String METHOD_GET_STATUS = "get_status";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET_STATUS.equals(method)) {
            return null;
        }
        return Qcom32VolteImsService.buildSanitizedStatusBundle(getContext());
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("VolteStatusProvider is strictly read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("VolteStatusProvider is strictly read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("VolteStatusProvider is strictly read-only");
    }
}
