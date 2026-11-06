package com.bitap.keyboard.ime;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

/**
 * Java bridge for the ViewTree owner setters.
 *
 * The Kotlin metadata of lifecycle 2.11 marks the ViewTree* helper classes as
 * implementation details, so Kotlin code in this module cannot import them even
 * though their JVM methods are public. Java has no such visibility restriction.
 */
public final class ViewTreeOwners {

    private ViewTreeOwners() {
    }

    public static void attach(
            View view,
            LifecycleOwner lifecycleOwner,
            ViewModelStoreOwner viewModelStoreOwner,
            SavedStateRegistryOwner savedStateRegistryOwner
    ) {
        ViewTreeLifecycleOwner.set(view, lifecycleOwner);
        ViewTreeViewModelStoreOwner.set(view, viewModelStoreOwner);
        ViewTreeSavedStateRegistryOwner.set(view, savedStateRegistryOwner);
    }
}
