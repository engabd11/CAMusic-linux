package android.content

/**
 * Desktop shim: [Context] appears in shared :app files only as an opaque token
 * passed between layers (LocalFavourites carries it for Android's file APIs).
 * Empty is honest — nothing on the desktop shared list dereferences it.
 */
class Context