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

package com.microsoft.gittf.core.util.tree;

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import java.io.UnsupportedEncodingException;

public class CommitTreePath
    implements Comparable<CommitTreePath>
{
    private final int type;
    private final String name;

    private final String fullName;
    private final int depth;

    private static char FOLDER_SUFFIX = '/';

    public CommitTreePath(String name, int type)
        throws UnsupportedEncodingException
    {
        this.name = name;
        this.type = type;

        if (this.type == OBJ_BLOB)
        {
            this.fullName = name;
            this.depth = 0;
        }
        else
        {
            this.fullName = name + FOLDER_SUFFIX;
            this.depth = calculateDepth();
        }
    }

    public String getName()
    {
        return name;
    }

    public String getFullName()
    {
        return fullName;
    }

    public int getType()
    {
        return type;
    }

    public int getDepth()
    {
        return depth;
    }

    private int calculateDepth()
    {
        char[] characters = this.getName().toCharArray();
        int count = 0;
        for (char character : characters)
        {
            if (character == FOLDER_SUFFIX)
            {
                count++;
            }
        }

        return count;
    }

    public boolean equals(Object obj)
    {
        if (!(obj instanceof CommitTreePath))
        {
            return false;
        }

        CommitTreePath other = (CommitTreePath) obj;

        return (this.name.equals(other.name) && (this.type == other.type));
    }

    public int hashCode()
    {
        return this.name != null ? this.name.hashCode() * 37 + type : type;
    }

    public int compareTo(CommitTreePath other)
    {
        if (this == other)
            return 0;

        return this.fullName.compareTo(other.fullName);
    }
}