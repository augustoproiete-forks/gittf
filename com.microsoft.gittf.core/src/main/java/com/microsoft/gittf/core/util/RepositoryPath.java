/***********************************************************************************************
 * Copyright (c) Microsoft Corporation All rights reserved.
 * 
 * MIT License:
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ***********************************************************************************************/

package com.microsoft.gittf.core.util;

import java.util.List;

/**
 * Repository path utility functions.
 * 
 */
public final class RepositoryPath
{
    /**
     * Constructor
     */
    private RepositoryPath()
    {

    }

    /**
     * The preferred separator character.
     */
    public static final char PREFERRED_SEPARATOR_CHARACTER = '/';
    public static final String PREFERRED_SEPARATOR_STRING = "/"; //$NON-NLS-1$

    /**
     * Allowed path separator characters in repository paths. All characters are
     * equivalent. Forward slash ('/') is the preferred character.
     */
    public static final char[] SEPARATOR_CHARACTERS =
    {
        '/', '\\'
    };

    /**
     * Gets just the folder part of the given repository path, which is all of
     * the string up to the last component (the file part). If the given path
     * describes a folder but does not end in a separator, the last folder is
     * discarded.
     * 
     * @param repositoryPath
     *        the repository path of which to return the folder part (must not
     *        be <code>null</code>)
     * @return a repository path with only the folder part of the given path,
     *         ending in a separator character.
     */
    public static String getParent(String repositoryPath)
    {
        Check.notNull(repositoryPath, "repositoryPath"); //$NON-NLS-1$

        int largestIndex = -1;
        for (int i = 0; i < RepositoryPath.SEPARATOR_CHARACTERS.length; i++)
        {
            largestIndex = Math.max(largestIndex, repositoryPath.lastIndexOf(RepositoryPath.SEPARATOR_CHARACTERS[i]));
        }

        if (largestIndex != -1)
        {
            return repositoryPath.substring(0, largestIndex);

        }

        return ""; //$NON-NLS-1$
    }

    /**
     * Gets just the file part of the given server path, which is all of the
     * string after the last path component. If there are no separators, the
     * entire string is returned. If the string ends in a separator, an empty
     * string is returned.
     * 
     * @param repositoryPath
     *        the repository path from which to parse the file part (must not be
     *        <code>null</code>)
     * @return the file name at the end of the given repository path, or the
     *         given path if no separator characters were found, or an empty
     *         string if the given path ends with a separator.
     */
    public static String getFileName(final String repositoryPath)
    {
        Check.notNull(repositoryPath, "repositoryPath"); //$NON-NLS-1$

        int largestIndex = -1;
        for (int i = 0; i < RepositoryPath.SEPARATOR_CHARACTERS.length; i++)
        {
            largestIndex = Math.max(largestIndex, repositoryPath.lastIndexOf(RepositoryPath.SEPARATOR_CHARACTERS[i]));
        }

        if (largestIndex == -1)
        {
            return repositoryPath;
        }

        /*
         * Add 1 to return the part after the sep, unless that would be longer
         * than the string ("$/foo/bar/" would be that case).
         */
        if (largestIndex + 1 < repositoryPath.length())
        {
            return repositoryPath.substring(largestIndex + 1);
        }
        else
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Returns the depth of the item described by path, where the root folder is
     * depth 0, team projects are at depth 1, and so on.
     * 
     * @param repositoryPath
     *        the repository path to test (must not be <code>null</code>)
     * @return the depth from root, where root is 0, team projects are 1, etc.
     */
    public static int getFolderDepth(final String repositoryPath)
    {
        return RepositoryPath.getFolderDepth(repositoryPath, Integer.MAX_VALUE);
    }

    /**
     * Returns the depth of the item described by path, where the root folder is
     * depth 0, team projects are at depth 1, "$/Foo/Bar" is 2, and so on.
     * 
     * @param repositoryPath
     *        the repository path to test (must not be <code>null</code>)
     * @param maxDepth
     *        the maximum depth to search.
     * @return the depth from root, where root is 0, team projects are 1, etc.
     */
    public static int getFolderDepth(final String repositoryPath, final int maxDepth)
    {
        Check.notNull(repositoryPath, "repositoryPath"); //$NON-NLS-1$

        int depth = 0;

        for (int i = repositoryPath.indexOf(PREFERRED_SEPARATOR_STRING); i != -1 && maxDepth > depth; i =
            repositoryPath.indexOf(PREFERRED_SEPARATOR_STRING, i + 1))
        {
            depth++;
        }

        return depth;
    }

    public static String getCommonPrefix(String path1, String path2, List<String> difference)
    {
        String[] path1Components = path1.split(PREFERRED_SEPARATOR_STRING);
        String[] path2Components = path2.split(PREFERRED_SEPARATOR_STRING);

        String commonPrefix = ""; //$NON-NLS-1$
        String path1Difference = ""; //$NON-NLS-1$
        String path2Difference = ""; //$NON-NLS-1$

        int longestLength = Math.max(path1Components.length, path2Components.length);
        boolean firstDifferenceFound = false;

        for (int count = 0; count < longestLength; count++)
        {
            String path1Component = count < path1Components.length ? path1Components[count] : ""; //$NON-NLS-1$
            String path2Component = count < path2Components.length ? path2Components[count] : ""; //$NON-NLS-1$

            if (path1Component.equals(path2Component) && path1Component.length() > 0 && !firstDifferenceFound)
            {
                commonPrefix += path1Component + PREFERRED_SEPARATOR_STRING;
            }
            else
            {
                firstDifferenceFound = true;
                if (path1Component.length() > 0)
                    path1Difference += path1Component + PREFERRED_SEPARATOR_STRING;

                if (path2Component.length() > 0)
                    path2Difference += path2Component + PREFERRED_SEPARATOR_STRING;
            }
        }

        if (difference != null)
        {
            difference.clear();
            difference.add(path1Difference.length() > 0 ? path1Difference.substring(0, path1Difference.length() - 1)
                : path1Difference);
            difference.add(path2Difference.length() > 0 ? path2Difference.substring(0, path2Difference.length() - 1)
                : path2Difference);
        }

        return commonPrefix.length() > 0 ? commonPrefix.substring(0, commonPrefix.length() - 1) : commonPrefix;
    }
}
