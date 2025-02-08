/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.quickstep.util;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;

import com.android.launcher3.util.IntArray;

import java.lang.annotation.Retention;
import java.util.List;

/**
 * Helper class for navigating RecentsView grid tasks via arrow keys and tab.
 */
public class TaskGridNavHelper {
    public static final int CLEAR_ALL_PLACEHOLDER_ID = -1;
    public static final int ADD_DESK_PLACEHOLDER_ID = -2;

    public static final int DIRECTION_UP = 0;
    public static final int DIRECTION_DOWN = 1;
    public static final int DIRECTION_LEFT = 2;
    public static final int DIRECTION_RIGHT = 3;
    public static final int DIRECTION_TAB = 4;

    @Retention(SOURCE)
    @IntDef({DIRECTION_UP, DIRECTION_DOWN, DIRECTION_LEFT, DIRECTION_RIGHT, DIRECTION_TAB})
    public @interface TASK_NAV_DIRECTION {}

    private final IntArray mOriginalTopRowIds;
    private final IntArray mTopRowIds = new IntArray();
    private final IntArray mBottomRowIds = new IntArray();

    public TaskGridNavHelper(IntArray topIds, IntArray bottomIds,
            List<Integer> largeTileIds, boolean hasAddDesktopButton) {
        mOriginalTopRowIds = topIds.clone();
        generateTaskViewIdGrid(topIds, bottomIds, largeTileIds, hasAddDesktopButton);
    }

    private void generateTaskViewIdGrid(IntArray topRowIdArray, IntArray bottomRowIdArray,
            List<Integer> largeTileIds, boolean hasAddDesktopButton) {
        // Add AddDesktopButton and lage tiles to both rows.
        if (hasAddDesktopButton) {
            mTopRowIds.add(ADD_DESK_PLACEHOLDER_ID);
            mBottomRowIds.add(ADD_DESK_PLACEHOLDER_ID);
        }
        for (Integer tileId : largeTileIds) {
            mTopRowIds.add(tileId);
            mBottomRowIds.add(tileId);
        }

        // Add row ids to their respective rows.
        mTopRowIds.addAll(topRowIdArray);
        mBottomRowIds.addAll(bottomRowIdArray);

        // Fill in the shorter array with the ids from the longer one.
        while (mTopRowIds.size() > mBottomRowIds.size()) {
            mBottomRowIds.add(mTopRowIds.get(mBottomRowIds.size()));
        }
        while (mBottomRowIds.size() > mTopRowIds.size()) {
            mTopRowIds.add(mBottomRowIds.get(mTopRowIds.size()));
        }

        // Add the clear all button to the end of both arrays.
        mTopRowIds.add(CLEAR_ALL_PLACEHOLDER_ID);
        mBottomRowIds.add(CLEAR_ALL_PLACEHOLDER_ID);
    }

    /**
     * Returns the id of the next page in the grid or -1 for the clear all button.
     */
    public int getNextGridPage(int currentPageTaskViewId, int delta,
            @TASK_NAV_DIRECTION int direction, boolean cycle) {
        boolean inTop = mTopRowIds.contains(currentPageTaskViewId);
        int index = inTop ? mTopRowIds.indexOf(currentPageTaskViewId)
                : mBottomRowIds.indexOf(currentPageTaskViewId);
        int maxSize = Math.max(mTopRowIds.size(), mBottomRowIds.size());
        int nextIndex = index + delta;

        switch (direction) {
            case DIRECTION_UP:
            case DIRECTION_DOWN: {
                return inTop ? mBottomRowIds.get(index) : mTopRowIds.get(index);
            }
            case DIRECTION_LEFT: {
                int boundedIndex = cycle ? nextIndex % maxSize : Math.min(nextIndex, maxSize - 1);
                return inTop ? mTopRowIds.get(boundedIndex)
                        : mBottomRowIds.get(boundedIndex);
            }
            case DIRECTION_RIGHT: {
                int boundedIndex =
                        cycle ? (nextIndex < 0 ? maxSize - 1 : nextIndex) : Math.max(
                                nextIndex, 0);
                boolean inOriginalTop = mOriginalTopRowIds.contains(currentPageTaskViewId);
                return inOriginalTop ? mTopRowIds.get(boundedIndex)
                        : mBottomRowIds.get(boundedIndex);
            }
            case DIRECTION_TAB: {
                int boundedIndex =
                        cycle ? nextIndex < 0 ? maxSize - 1 : nextIndex % maxSize : Math.min(
                                nextIndex, maxSize - 1);
                if (delta >= 0) {
                    return inTop && mTopRowIds.get(index) != mBottomRowIds.get(index)
                            ? mBottomRowIds.get(index)
                            : mTopRowIds.get(boundedIndex);
                } else {
                    if (mTopRowIds.contains(currentPageTaskViewId)) {
                        return mBottomRowIds.get(boundedIndex);
                    } else {
                        // Go up to top if there is task above
                        return mTopRowIds.get(index) != mBottomRowIds.get(index)
                                ? mTopRowIds.get(index)
                                : mBottomRowIds.get(boundedIndex);
                    }
                }
            }
            default:
                return currentPageTaskViewId;
        }
    }
}
