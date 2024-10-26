/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.model;

import android.content.ContentValues;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.launcher3.LauncherSettings;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.ContentWriter;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DbEntry extends ItemInfo implements Comparable<DbEntry> {

    private static final String TAG = "DbEntry";

    String mIntent;
    String mProvider;
    Map<String, Set<Integer>> mFolderItems = new HashMap<>();

    /**
     * Id of the specific widget.
     */
    public int appWidgetId = NO_ID;

    /** Comparator according to the reading order */
    @Override
    public int compareTo(DbEntry another) {
        if (screenId != another.screenId) {
            return Integer.compare(screenId, another.screenId);
        }
        if (cellY != another.cellY) {
            return Integer.compare(cellY, another.cellY);
        }
        return Integer.compare(cellX, another.cellX);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DbEntry)) return false;
        DbEntry entry = (DbEntry) o;
        return Objects.equals(getEntryMigrationId(), entry.getEntryMigrationId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getEntryMigrationId());
    }

    /**
     *  Puts the updated DbEntry values into ContentValues which we then use to insert the
     *  entry to the DB.
     */
    public void updateContentValues(ContentValues values) {
        values.put(LauncherSettings.Favorites.SCREEN, screenId);
        values.put(LauncherSettings.Favorites.CELLX, cellX);
        values.put(LauncherSettings.Favorites.CELLY, cellY);
        values.put(LauncherSettings.Favorites.SPANX, spanX);
        values.put(LauncherSettings.Favorites.SPANY, spanY);
    }

    @Override
    public void writeToValues(@NonNull ContentWriter writer) {
        super.writeToValues(writer);
        writer.put(LauncherSettings.Favorites.APPWIDGET_ID, appWidgetId);
    }

    @Override
    public void readFromValues(@NonNull ContentValues values) {
        super.readFromValues(values);
        appWidgetId = values.getAsInteger(LauncherSettings.Favorites.APPWIDGET_ID);
    }

    /**
     * This id is not used in the DB is only used while doing the migration and it identifies
     * an entry on each workspace. For example two calculator icons would have the same
     * migration id even thought they have different database ids.
     */
    public String getEntryMigrationId() {
        switch (itemType) {
            case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
            case LauncherSettings.Favorites.ITEM_TYPE_APP_PAIR:
                return getFolderMigrationId();
            case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                // mProvider is the app the widget belongs to and appWidgetId it's the unique
                // is of the widget, we need both because if you remove a widget and then add it
                // again, then it can change and the WidgetProvider would not know the widget.
                return mProvider + appWidgetId;
            case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                final String intentStr = cleanIntentString(mIntent);
                try {
                    Intent i = Intent.parseUri(intentStr, 0);
                    return Objects.requireNonNull(i.getComponent()).toString();
                } catch (Exception e) {
                    return intentStr;
                }
            default:
                return cleanIntentString(mIntent);
        }
    }

    /**
     * This method should return an id that should be the same for two folders containing the
     * same elements.
     */
    @NonNull
    private String getFolderMigrationId() {
        return mFolderItems.keySet().stream()
                .map(intentString -> mFolderItems.get(intentString).size()
                        + cleanIntentString(intentString))
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * This is needed because sourceBounds can change and make the id of two equal items
     * different.
     */
    @NonNull
    private String cleanIntentString(@NonNull String intentStr) {
        try {
            Intent i = Intent.parseUri(intentStr, 0);
            i.setSourceBounds(null);
            return i.toURI();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Unable to parse Intent string", e);
            return intentStr;
        }

    }
}
