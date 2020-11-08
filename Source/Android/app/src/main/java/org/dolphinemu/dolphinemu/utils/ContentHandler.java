package org.dolphinemu.dolphinemu.utils;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.annotation.Keep;

import org.dolphinemu.dolphinemu.DolphinApplication;

import java.io.FileNotFoundException;
import java.util.List;

public class ContentHandler
{
  @Keep
  public static int openFd(@NonNull String uri, @NonNull String mode)
  {
    try
    {
      return getContentResolver().openFileDescriptor(unmangle(uri), mode).detachFd();
    }
    catch (SecurityException e)
    {
      Log.error("Tried to open " + uri + " without permission");
      return -1;
    }
    // Some content providers throw IllegalArgumentException for invalid modes,
    // despite the documentation saying that invalid modes result in a FileNotFoundException
    catch (FileNotFoundException | IllegalArgumentException | NullPointerException e)
    {
      return -1;
    }
  }

  @Keep
  public static boolean delete(@NonNull String uri)
  {
    try
    {
      return DocumentsContract.deleteDocument(getContentResolver(), unmangle(uri));
    }
    catch (SecurityException e)
    {
      Log.error("Tried to delete " + uri + " without permission");
      return false;
    }
    catch (FileNotFoundException e)
    {
      // Return true because we care about the file not being there, not the actual delete.
      return true;
    }
  }

  public static boolean exists(@NonNull String uri)
  {
    try
    {
      Uri documentUri = treeToDocument(unmangle(uri));
      final String[] projection = new String[]{Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
      try (Cursor cursor = getContentResolver().query(documentUri, projection, null, null, null))
      {
        return cursor != null && cursor.getCount() > 0;
      }
    }
    catch (SecurityException e)
    {
      Log.error("Tried to check if " + uri + " exists without permission");
    }
    catch (FileNotFoundException ignored)
    {
    }

    return false;
  }

  /**
   * @return -1 if not found, -2 if directory, file size otherwise
   */
  @Keep
  public static long getSizeAndIsDirectory(@NonNull String uri)
  {
    try
    {
      Uri documentUri = treeToDocument(unmangle(uri));
      final String[] projection = new String[]{Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
      try (Cursor cursor = getContentResolver().query(documentUri, projection, null, null, null))
      {
        if (cursor != null && cursor.moveToFirst())
        {
          if (Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
            return -2;
          else
            return cursor.isNull(1) ? 0 : cursor.getLong(1);
        }
      }
    }
    catch (SecurityException e)
    {
      Log.error("Tried to get metadata for " + uri + " without permission");
    }
    catch (FileNotFoundException ignored)
    {
    }

    return -1;
  }

  @Nullable @Keep
  public static String getDisplayName(@NonNull String uri)
  {
    try
    {
      return getDisplayName(unmangle(uri));
    }
    catch (FileNotFoundException e)
    {
      return null;
    }
  }

  @Nullable
  public static String getDisplayName(@NonNull Uri uri)
  {
    final String[] projection = new String[]{Document.COLUMN_DISPLAY_NAME};
    Uri documentUri = treeToDocument(uri);
    try (Cursor cursor = getContentResolver().query(documentUri, projection, null, null, null))
    {
      if (cursor != null && cursor.moveToFirst())
      {
        return cursor.getString(0);
      }
    }
    catch (SecurityException e)
    {
      Log.error("Tried to get display name of " + uri + " without permission");
    }

    return null;
  }

  @NonNull @Keep
  public static String[] getChildNames(@NonNull String uri)
  {
    try
    {
      Uri unmangledUri = unmangle(uri);
      String documentId = DocumentsContract.getDocumentId(treeToDocument(unmangledUri));
      Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(unmangledUri, documentId);

      final String[] projection = new String[]{Document.COLUMN_DISPLAY_NAME};
      try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null))
      {
        if (cursor != null)
        {
          String[] result = new String[cursor.getCount()];
          for (int i = 0; i < result.length; i++)
          {
            cursor.moveToNext();
            result[i] = cursor.getString(0);
          }
          return result;
        }
      }
    }
    catch (SecurityException e)
    {
      Log.error("Tried to get children of " + uri + " without permission");
    }
    catch (FileNotFoundException ignored)
    {
    }

    return new String[0];
  }

  @NonNull
  private static Uri getChild(@NonNull Uri parentUri, @NonNull String childName)
          throws FileNotFoundException, SecurityException
  {
    String parentId = DocumentsContract.getDocumentId(treeToDocument(parentUri));
    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId);

    final String[] projection = new String[]{Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_DOCUMENT_ID};
    final String selection = Document.COLUMN_DISPLAY_NAME + "=?";
    final String[] selectionArgs = new String[]{childName};
    try (Cursor cursor = getContentResolver().query(childrenUri, projection, selection,
            selectionArgs, null))
    {
      if (cursor != null)
      {
        while (cursor.moveToNext())
        {
          // FileProvider seemingly doesn't support selections, so we have to manually filter here
          if (childName.equals(cursor.getString(0)))
          {
            return DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(1));
          }
        }
      }
    }
    catch (SecurityException e)
    {
      Log.error("Tried to get child " + childName + " of " + parentUri + " without permission");
    }

    throw new FileNotFoundException(parentUri + "/" + childName);
  }

  /**
   * Since our C++ code was written under the assumption that it would be running under a filesystem
   * which supports normal paths, it appends a slash followed by a file name when it wants to access
   * a file in a directory. This function translates that into the type of URI that SAF requires.
   *
   * In order to detect whether a URI is mangled or not, we make the assumption that an
   * unmangled URI contains at least one % and does not contain any slashes after the last %.
   * This seems to hold for all common storage providers, but it is theoretically for a storage
   * provider to use URIs without any % characters.
   */
  @NonNull
  private static Uri unmangle(@NonNull String uri) throws FileNotFoundException, SecurityException
  {
    int lastComponentEnd = getLastComponentEnd(uri);
    int lastComponentStart = getLastComponentStart(uri, lastComponentEnd);

    if (lastComponentStart == 0)
    {
      return Uri.parse(uri.substring(0, lastComponentEnd));
    }
    else
    {
      Uri parentUri = unmangle(uri.substring(0, lastComponentStart));
      String childName = uri.substring(lastComponentStart, lastComponentEnd);
      return getChild(parentUri, childName);
    }
  }

  /**
   * Returns the last character which is not a slash.
   */
  private static int getLastComponentEnd(@NonNull String uri)
  {
    int i = uri.length();
    while (i > 0 && uri.charAt(i - 1) == '/')
      i--;
    return i;
  }

  /**
   * Scans backwards starting from lastComponentEnd and returns the index after the first slash
   * it finds, but only if there is a % before that slash and there is no % after it.
   */
  private static int getLastComponentStart(@NonNull String uri, int lastComponentEnd)
  {
    int i = lastComponentEnd;
    while (i > 0 && uri.charAt(i - 1) != '/')
    {
      i--;
      if (uri.charAt(i) == '%')
        return 0;
    }

    int j = i;
    while (j > 0)
    {
      j--;
      if (uri.charAt(j) == '%')
        return i;
    }

    return 0;
  }

  @NonNull
  private static Uri treeToDocument(@NonNull Uri uri)
  {
    if (isTreeUri(uri))
    {
      String documentId = DocumentsContract.getTreeDocumentId(uri);
      return DocumentsContract.buildDocumentUriUsingTree(uri, documentId);
    }
    else
    {
      return uri;
    }
  }

  /**
   * This is like DocumentsContract.isTreeUri, except it doesn't return true for URIs like
   * content://com.example/tree/12/document/24/. We want to treat those as documents, not trees.
   */
  private static boolean isTreeUri(@NonNull Uri uri)
  {
    final List<String> pathSegments = uri.getPathSegments();
    return pathSegments.size() == 2 && "tree".equals(pathSegments.get(0));
  }

  private static ContentResolver getContentResolver()
  {
    return DolphinApplication.getAppContext().getContentResolver();
  }
}
