/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.support.v7.internal.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Build;
import android.support.v4.view.ViewCompat;
import android.support.v7.appcompat.R;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @hide
 */
public class ViewUtils {
    private static final String TAG = "ViewUtils";

    private static Method sComputeFitSystemWindowsMethod;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try {
                sComputeFitSystemWindowsMethod = View.class.getDeclaredMethod(
                        "computeFitSystemWindows", Rect.class, Rect.class);
                if (!sComputeFitSystemWindowsMethod.isAccessible()) {
                    sComputeFitSystemWindowsMethod.setAccessible(true);
                }
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "Could not find method computeFitSystemWindows. Oh well.");
            }
        }
    }

    private ViewUtils() {}

    public static boolean isLayoutRtl(View view) {
        return ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL;
    }

    /**
     * Merge two states as returned by {@link ViewCompat#getMeasuredState(android.view.View)} ()}.
     * @param curState The current state as returned from a view or the result
     * of combining multiple views.
     * @param newState The new view state to combine.
     * @return Returns a new integer reflecting the combination of the two
     * states.
     */
    public static int combineMeasuredStates(int curState, int newState) {
        return curState | newState;
    }

    /**
     * Allow calling the hidden method {@code computeFitSystemWindows(Rect, Rect)} through
     * reflection on {@code view}.
     */
    public static void computeFitSystemWindows(View view, Rect inoutInsets, Rect outLocalInsets) {
        if (sComputeFitSystemWindowsMethod != null) {
            try {
                sComputeFitSystemWindowsMethod.invoke(view, inoutInsets, outLocalInsets);
            } catch (Exception e) {
                Log.d(TAG, "Could not invoke computeFitSystemWindows", e);
            }
        }
    }

    /**
     * Allow calling the hidden method {@code makeOptionalFitsSystem()} through reflection on
     * {@code view}.
     */
    public static void makeOptionalFitsSystemWindows(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                // We need to use getMethod() for makeOptionalFitsSystemWindows since both View
                // and ViewGroup implement the method
                Method method = view.getClass().getMethod("makeOptionalFitsSystemWindows");
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                method.invoke(view);
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "Could not find method makeOptionalFitsSystemWindows. Oh well...");
            } catch (InvocationTargetException e) {
                Log.d(TAG, "Could not invoke makeOptionalFitsSystemWindows", e);
            } catch (IllegalAccessException e) {
                Log.d(TAG, "Could not invoke makeOptionalFitsSystemWindows", e);
            }
        }
    }

    /**
     * Allows us to emulate the {@code android:theme} attribute for devices before L.
     */
    public static Context themifyContext(Context context, AttributeSet attrs) {
        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.View, 0, 0);
        // First try reading android:theme
        int themeId = a.getResourceId(R.styleable.View_android_theme, 0);
        if (themeId == 0) {
            // ...if that didn't work, try reading app:theme (for legacy reasons)
            themeId = a.getResourceId(R.styleable.View_theme, 0);
        }
        a.recycle();

        if (themeId != 0 && (!(context instanceof ContextThemeWrapperCompat)
                || ((ContextThemeWrapperCompat) context).getThemesResId() != themeId)) {
            // If the context isn't a ContextThemeWrapperCompat, or it is but does not have
            // the same theme as we need, wrap it in a new wrapper
            context = new ContextThemeWrapperCompat(context, themeId);
        }
        return context;
    }

    static class ContextThemeWrapperCompat extends ContextThemeWrapper {

        private int mThemesResId;

        public ContextThemeWrapperCompat(Context base, int themeres) {
            super(base, themeres);
            mThemesResId = themeres;
        }

        @Override
        public void setTheme(int resid) {
            super.setTheme(resid);
            mThemesResId = resid;
        }

        public int getThemesResId() {
            return mThemesResId;
        }
    }
}
