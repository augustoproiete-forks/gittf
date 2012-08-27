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

package com.microsoft.gittf.core;

public final class GitTFConstants
{
    public static final int GIT_TF_CURRENT_FORMAT_VERSION = 1;
    public static final String GIT_TF_NAME = "git-tf"; //$NON-NLS-1$
    public static final String GIT_TF_DIRNAME = "tf"; //$NON-NLS-1$
    public static final boolean GIT_TF_DEFAULT_DEEP = false;
    public static final int GIT_TF_SHALLOW_DEPTH = 1;
    public static final String GIT_TF_REMOTE = "origin_tfs/"; //$NON-NLS-1$
    public static final String GIT_TF_BRANCHNAME = "tfs"; //$NON-NLS-1$

    private GitTFConstants()
    {
    }
}
