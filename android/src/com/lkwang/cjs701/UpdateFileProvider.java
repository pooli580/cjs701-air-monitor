package com.lkwang.cjs701;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateFileProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://com.lkwang.cjs701.cloud.update/update.apk");
    static final String APK_TYPE = "application/vnd.android.package-archive";

    static File file(Context context) {
        return new File(context.getCacheDir(), "online-update-verified.apk");
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return URI.equals(uri) ? APK_TYPE : null; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!URI.equals(uri) || !"r".equals(mode)) throw new FileNotFoundException("Invalid update file request");
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!URI.equals(uri)) throw new IllegalArgumentException("Invalid update file request");
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File apk = file(getContext());
        if (apk.isFile()) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add("CJS701-update.apk");
                else if (OpenableColumns.SIZE.equals(column)) row.add(apk.length());
                else row.add(null);
            }
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
