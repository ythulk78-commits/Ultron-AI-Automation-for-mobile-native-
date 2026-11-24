package com.ultron.assistant;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

/**
 * PHASE 5 - Resolves "[Contact Name] ko call karo" to a phone number via ContentResolver,
 * instead of automating the Contacts app's UI. More reliable than accessibility node-hunting
 * and needs only READ_CONTACTS, not a multi-step UI flow. Requires the user to have granted
 * the Contacts permission (MainActivity's permission list should include it - see README).
 */
public class ContactsHelper {

    private static final String TAG = "Ultron.Contacts";

    /** Returns the first matching phone number for a spoken contact name, or null if none found. */
    public static String lookupNumber(Context context, String contactName) {
        if (contactName == null || contactName.trim().isEmpty()) return null;

        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        };
        String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
        String[] selectionArgs = {"%" + contactName.trim() + "%"};

        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                return cursor.getString(numberIdx);
            }
        } catch (Exception e) {
            Log.e(TAG, "Contact lookup failed", e);
        }
        return null;
    }
}
