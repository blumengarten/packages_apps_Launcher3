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

import static com.android.launcher3.Flags.enableSmartspaceRemovalToggle;
import static com.android.launcher3.LauncherPrefs.IS_FIRST_LOAD_AFTER_RESTORE;
import static com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME;
import static com.android.launcher3.LauncherSettings.Favorites.TMP_TABLE;
import static com.android.launcher3.Utilities.SHOULD_SHOW_FIRST_PAGE_WIDGET;
import static com.android.launcher3.model.GridSizeMigrationDBController.copyCurrentGridToNewGrid;
import static com.android.launcher3.model.GridSizeMigrationDBController.insertEntryInDb;
import static com.android.launcher3.model.GridSizeMigrationDBController.needsToMigrate;
import static com.android.launcher3.model.GridSizeMigrationDBController.removeEntryFromDb;
import static com.android.launcher3.model.LoaderTask.SMARTSPACE_ON_HOME_SCREEN;
import static com.android.launcher3.provider.LauncherDbUtils.copyTable;
import static com.android.launcher3.provider.LauncherDbUtils.dropTable;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Point;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.launcher3.Flags;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.provider.LauncherDbUtils;
import com.android.launcher3.util.CellAndSpan;
import com.android.launcher3.util.GridOccupancy;
import com.android.launcher3.util.IntArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GridSizeMigrationLogic {

    private static final String TAG = "GridSizeMigrationLogic";
    private static final boolean DEBUG = true;

    /**
     * Migrates the grid size from srcDeviceState to destDeviceState and make those changes
     * in the target DB, using the source DB to determine what to add/remove/move/resize
     * in the destination DB.
     */
    public void migrateGrid(
            @NonNull Context context,
            @NonNull DeviceGridState srcDeviceState,
            @NonNull DeviceGridState destDeviceState,
            @NonNull DatabaseHelper target,
            @NonNull SQLiteDatabase source) {
        if (!needsToMigrate(srcDeviceState, destDeviceState)) {
            return;
        }

        boolean isFirstLoad = LauncherPrefs.get(context).get(IS_FIRST_LOAD_AFTER_RESTORE);
        Log.d(TAG, "Begin grid migration. First load: " + isFirstLoad);

        // This is a special case where if the grid is the same amount of columns but a larger
        // amount of rows we simply copy over the source grid to the destination grid, rather
        // than undergoing the general grid migration.
        if (shouldMigrateToStrictlyTallerGrid(isFirstLoad, srcDeviceState, destDeviceState)) {
            copyCurrentGridToNewGrid(context, destDeviceState, target, source);
            return;
        }
        copyTable(source, TABLE_NAME, target.getWritableDatabase(), TMP_TABLE, context);

        long migrationStartTime = System.currentTimeMillis();
        try (LauncherDbUtils.SQLiteTransaction t =
                     new LauncherDbUtils.SQLiteTransaction(target.getWritableDatabase())) {
            GridSizeMigrationDBController.DbReader srcReader = new GridSizeMigrationDBController
                    .DbReader(t.getDb(), TMP_TABLE, context);
            GridSizeMigrationDBController.DbReader destReader =
                    new GridSizeMigrationDBController.DbReader(
                            t.getDb(), TABLE_NAME, context);

            Point targetSize = new Point(destDeviceState.getColumns(), destDeviceState.getRows());
            // Migrate hotseat.
            migrateHotseat(destDeviceState.getNumHotseat(), srcReader, destReader, target);
            // Migrate workspace.
            migrateWorkspace(srcReader, destReader, target, targetSize);

            dropTable(t.getDb(), TMP_TABLE);
            t.commit();
        } catch (Exception e) {
            Log.e(TAG, "Error during grid migration", e);
        } finally {
            Log.v(TAG, "Workspace migration completed in "
                    + (System.currentTimeMillis() - migrationStartTime));

            // Save current configuration, so that the migration does not run again.
            destDeviceState.writeToPrefs(context);
        }
    }

    private void migrateHotseat(int destHotseatSize,
            GridSizeMigrationDBController.DbReader srcReader,
            GridSizeMigrationDBController.DbReader destReader, DatabaseHelper helper) {
        final List<DbEntry> srcHotseatItems =
                srcReader.loadHotseatEntries();
        final List<DbEntry> dstHotseatItems =
                destReader.loadHotseatEntries();


        final List<DbEntry> hotseatToBeAdded =
                getItemsToBeAdded(srcHotseatItems, dstHotseatItems);
        final IntArray toBeRemoved = new IntArray();
        toBeRemoved.addAll(getItemsToBeRemoved(srcHotseatItems, dstHotseatItems));

        if (DEBUG) {
            Log.d(TAG, "Start hotseat migration:"
                    + "\n Removing Hotseat Items:"
                    + dstHotseatItems.stream().filter(entry -> toBeRemoved
                    .contains(entry.id)).map(DbEntry::toString)
                    .collect(Collectors.joining(",\n", "[", "]"))
                    + "\n Adding Hotseat Items:"
                    + hotseatToBeAdded.stream().map(DbEntry::toString)
                    .collect(Collectors.joining(",\n", "[", "]"))
            );
        }

        // Removes the items that we need to remove from the destination DB.
        if (!toBeRemoved.isEmpty()) {
            removeEntryFromDb(destReader.mDb, destReader.mTableName, toBeRemoved);
        }

        placeHotseatItems(
                hotseatToBeAdded, dstHotseatItems, destHotseatSize, helper, srcReader, destReader);
    }

    private void placeHotseatItems(List<DbEntry> hotseatToBeAdded,
            List<DbEntry> dstHotseatItems, int destHotseatSize,
            DatabaseHelper helper, GridSizeMigrationDBController.DbReader srcReader,
            GridSizeMigrationDBController.DbReader destReader) {
        if (hotseatToBeAdded.isEmpty()) {
            return;
        }

        List<Integer> idsInUse = dstHotseatItems.stream().map(entry -> entry.id).toList();

        Collections.sort(hotseatToBeAdded);

        List<DbEntry> placementSolutionHotseat =
                solveHotseatPlacement(destHotseatSize, dstHotseatItems, hotseatToBeAdded);
        for (DbEntry entryToPlace: placementSolutionHotseat) {
            insertEntryInDb(helper, entryToPlace, srcReader.mTableName, destReader.mTableName,
                    idsInUse);
        }
    }

    private void migrateWorkspace(GridSizeMigrationDBController.DbReader srcReader,
            GridSizeMigrationDBController.DbReader destReader, DatabaseHelper helper,
            Point targetSize) {


        final List<DbEntry> srcWorkspaceItems =
                srcReader.loadAllWorkspaceEntries();

        final List<DbEntry> dstWorkspaceItems =
                destReader.loadAllWorkspaceEntries();

        final IntArray toBeRemoved = new IntArray();

        List<DbEntry> workspaceToBeAdded =
                getItemsToBeAdded(srcWorkspaceItems, dstWorkspaceItems);
        toBeRemoved.addAll(getItemsToBeRemoved(srcWorkspaceItems, dstWorkspaceItems));

        if (DEBUG) {
            Log.d(TAG, "Start workspace migration:"
                    + "\n Source Device:"
                    + srcWorkspaceItems.stream().map(
                            DbEntry::toString)
                    .collect(Collectors.joining(",\n", "[", "]"))
                    + "\n Target Device:"
                    + dstWorkspaceItems.stream().map(
                            DbEntry::toString)
                    .collect(Collectors.joining(",\n", "[", "]"))
                    + "\n Removing Workspace Items:"
                    + dstWorkspaceItems.stream().filter(entry -> toBeRemoved
                            .contains(entry.id)).map(
                            DbEntry::toString)
                    .collect(Collectors.joining(",\n", "[", "]"))
                    + "\n Adding Workspace Items:"
                    + workspaceToBeAdded.stream().map(
                            DbEntry::toString)
                    .collect(Collectors.joining(",\n", "[", "]"))
            );
        }

        // Removes the items that we need to remove from the destination DB.
        if (!toBeRemoved.isEmpty()) {
            removeEntryFromDb(destReader.mDb, destReader.mTableName, toBeRemoved);
        }

        placeWorkspaceItems(workspaceToBeAdded, dstWorkspaceItems, targetSize.x, targetSize.y,
                helper, srcReader, destReader);
    }

    private void placeWorkspaceItems(
            List<DbEntry> workspaceToBeAdded,
            List<DbEntry> dstWorkspaceItems,
            int trgX, int trgY, DatabaseHelper helper,
            GridSizeMigrationDBController.DbReader srcReader,
            GridSizeMigrationDBController.DbReader destReader) {
        if (workspaceToBeAdded.isEmpty()) {
            return;
        }

        List<Integer> idsInUse = dstWorkspaceItems.stream().map(entry -> entry.id).collect(
                Collectors.toList());

        Collections.sort(workspaceToBeAdded);


        // First we create a collection of the screens
        List<Integer> screens = new ArrayList<>();
        for (int screenId = 0; screenId <= destReader.mLastScreenId; screenId++) {
            screens.add(screenId);
        }

        // Then we place the items on the screens
        WorkspaceItemsToPlace itemsToPlace =
                new WorkspaceItemsToPlace(workspaceToBeAdded);
        for (int screenId : screens) {
            if (DEBUG) {
                Log.d(TAG, "Migrating " + screenId);
            }
            itemsToPlace = solveGridPlacement(
                    destReader.mContext, screenId, trgX, trgY, itemsToPlace.mRemainingItemsToPlace,
                    destReader.mWorkspaceEntriesByScreenId.get(screenId));
            placeItems(itemsToPlace, helper, srcReader, destReader, idsInUse);
            while (!itemsToPlace.mPlacementSolution.isEmpty()) {
                insertEntryInDb(helper, itemsToPlace.mPlacementSolution.remove(0),
                        srcReader.mTableName, destReader.mTableName, idsInUse);
            }
            if (itemsToPlace.mRemainingItemsToPlace.isEmpty()) {
                break;
            }
        }

        // In case the new grid is smaller, there might be some leftover items that don't fit on
        // any of the screens, in this case we add them to new screens until all of them are placed.
        int screenId = destReader.mLastScreenId + 1;
        while (!itemsToPlace.mRemainingItemsToPlace.isEmpty()) {
            itemsToPlace = solveGridPlacement(destReader.mContext, screenId,
                    trgX, trgY, itemsToPlace.mRemainingItemsToPlace,
                    destReader.mWorkspaceEntriesByScreenId.get(screenId));
            placeItems(itemsToPlace, helper, srcReader, destReader, idsInUse);
            screenId++;
        }
    }

    private void placeItems(WorkspaceItemsToPlace itemsToPlace, DatabaseHelper helper,
            GridSizeMigrationDBController.DbReader srcReader,
            GridSizeMigrationDBController.DbReader destReader, List<Integer> idsInUse) {
        while (!itemsToPlace.mPlacementSolution.isEmpty()) {
            insertEntryInDb(helper, itemsToPlace.mPlacementSolution.remove(0),
                    srcReader.mTableName, destReader.mTableName, idsInUse);
        }
    }


    /**
     * Only migrate the grid in this manner if the target grid is taller and not wider.
     */
    private boolean shouldMigrateToStrictlyTallerGrid(boolean isFirstLoad,
            @NonNull DeviceGridState srcDeviceState,
            @NonNull DeviceGridState destDeviceState) {
        if (isFirstLoad
                && Flags.enableGridMigrationFix()
                && srcDeviceState.getColumns().equals(destDeviceState.getColumns())
                && srcDeviceState.getRows() < destDeviceState.getRows()) {
            return true;
        }
        return false;
    }

    /**
     * Finds all the items that are in the old grid which aren't in the new grid, meaning they
     * need to be added to the new grid.
     *
     * @return a list of DbEntry's which we need to add.
     */
    private List<DbEntry> getItemsToBeAdded(
            @NonNull final List<DbEntry> src,
            @NonNull final List<DbEntry> dest) {
        Map<DbEntry, Integer> entryCountDiff =
                calcDiff(src, dest);
        List<DbEntry> toBeAdded = new ArrayList<>();
        src.forEach(entry -> {
            if (entryCountDiff.get(entry) > 0) {
                toBeAdded.add(entry);
                entryCountDiff.put(entry, entryCountDiff.get(entry) - 1);
            }
        });
        return toBeAdded;
    }

    /**
     * Finds all the items that are in the new grid which aren't in the old grid, meaning they
     * need to be removed from the new grid.
     *
     * @return an IntArray of item id's which we need to remove.
     */
    private IntArray getItemsToBeRemoved(
            @NonNull final List<DbEntry> src,
            @NonNull final List<DbEntry> dest) {
        Map<DbEntry, Integer> entryCountDiff =
                calcDiff(src, dest);
        IntArray toBeRemoved = new IntArray();
        dest.forEach(entry -> {
            if (entryCountDiff.get(entry) < 0) {
                toBeRemoved.add(entry.id);
                if (entry.itemType == LauncherSettings.Favorites.ITEM_TYPE_FOLDER) {
                    entry.mFolderItems.values().forEach(ids -> ids.forEach(toBeRemoved::add));
                }
                entryCountDiff.put(entry, entryCountDiff.get(entry) + 1);
            }
        });
        return toBeRemoved;
    }

    /**
     * Calculates the difference between the old and new grid items in terms of how many of each
     * item there are. E.g. if the old grid had 2 Calculator icons but the new grid has 0, then the
     * difference there would be 2. While if the old grid has 0 Calculator icons and the
     * new grid has 1, then the difference would be -1.
     *
     * @return a Map with each DbEntry as a key and the count of said entry as the value.
     */
    private Map<DbEntry, Integer> calcDiff(
            @NonNull final List<DbEntry> src,
            @NonNull final List<DbEntry> dest) {
        Map<DbEntry, Integer> entryCountDiff = new HashMap<>();
        src.forEach(entry ->
                entryCountDiff.put(entry, entryCountDiff.getOrDefault(entry, 0) + 1));
        dest.forEach(entry ->
                entryCountDiff.put(entry, entryCountDiff.getOrDefault(entry, 0) - 1));
        return entryCountDiff;
    }

    private List<DbEntry> solveHotseatPlacement(final int hotseatSize,
            @NonNull final List<DbEntry> placedHotseatItems,
            @NonNull final List<DbEntry> itemsToPlace) {
        List<DbEntry> placementSolution = new ArrayList<>();
        List<DbEntry> remainingItemsToPlace =
                new ArrayList<>(itemsToPlace);
        final boolean[] occupied = new boolean[hotseatSize];
        for (DbEntry entry : placedHotseatItems) {
            occupied[entry.screenId] = true;
        }

        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i] && !remainingItemsToPlace.isEmpty()) {
                DbEntry entry = remainingItemsToPlace.remove(0);
                entry.screenId = i;
                // These values does not affect the item position, but we should set them
                // to something other than -1.
                entry.cellX = i;
                entry.cellY = 0;

                placementSolution.add(entry);
                occupied[entry.screenId] = true;
            }
        }
        return placementSolution;
    }

    private WorkspaceItemsToPlace solveGridPlacement(
            Context context,
            final int screenId, final int trgX, final int trgY,
            @NonNull final List<DbEntry> sortedItemsToPlace,
            List<DbEntry> existedEntries) {
        WorkspaceItemsToPlace itemsToPlace = new WorkspaceItemsToPlace(sortedItemsToPlace);
        final GridOccupancy occupied = new GridOccupancy(trgX, trgY);
        final Point trg = new Point(trgX, trgY);
        final Point next = new Point(0, screenId == 0
                && (FeatureFlags.QSB_ON_FIRST_SCREEN
                && (!enableSmartspaceRemovalToggle() || LauncherPrefs.getPrefs(context)
                .getBoolean(SMARTSPACE_ON_HOME_SCREEN, true))
                && !SHOULD_SHOW_FIRST_PAGE_WIDGET)
                ? 1 /* smartspace */ : 0);
        if (existedEntries != null) {
            for (DbEntry entry : existedEntries) {
                occupied.markCells(entry, true);
            }
        }
        Iterator<DbEntry> iterator =
                itemsToPlace.mRemainingItemsToPlace.iterator();
        while (iterator.hasNext()) {
            final DbEntry entry = iterator.next();
            if (entry.minSpanX > trgX || entry.minSpanY > trgY) {
                iterator.remove();
                continue;
            }
            CellAndSpan placement = findPlacementForEntry(
                    entry, next.x, next.y, trg, occupied);
            if (placement != null) {
                entry.screenId = screenId;
                entry.cellX = placement.cellX;
                entry.cellY = placement.cellY;
                entry.spanX = placement.spanX;
                entry.spanY = placement.spanY;
                occupied.markCells(entry, true);
                next.set(entry.cellX + entry.spanX, entry.cellY);
                itemsToPlace.mPlacementSolution.add(entry);
                iterator.remove();
            }
        }
        return itemsToPlace;
    }

    /**
     * Search for the next possible placement of an item. (mNextStartX, mNextStartY) serves as
     * a memoization of last placement, we can start our search for next placement from there
     * to speed up the search.
     *
     * @return NewEntryPlacement object if we found a valid placement, null if we didn't.
     */
    private CellAndSpan findPlacementForEntry(
            @NonNull final DbEntry entry,
            int startPosX, int startPosY, @NonNull final Point trg,
            @NonNull final GridOccupancy occupied) {
        for (int y = startPosY; y <  trg.y; y++) {
            for (int x = startPosX; x < trg.x; x++) {
                boolean minFits = occupied.isRegionVacant(x, y, entry.minSpanX, entry.minSpanY);
                if (minFits) {
                    return (new CellAndSpan(x, y, entry.minSpanX, entry.minSpanY));
                }
            }
            startPosX = 0;
        }
        return null;
    }

    private static class WorkspaceItemsToPlace {
        List<DbEntry> mRemainingItemsToPlace;
        List<DbEntry> mPlacementSolution;

        WorkspaceItemsToPlace(List<DbEntry> sortedItemsToPlace) {
            mRemainingItemsToPlace = new ArrayList<>(sortedItemsToPlace);
            mPlacementSolution = new ArrayList<>();
        }

    }
}
