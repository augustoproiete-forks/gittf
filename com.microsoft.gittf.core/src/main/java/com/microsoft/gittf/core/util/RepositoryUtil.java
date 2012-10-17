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

import static org.eclipse.jgit.lib.Constants.DOT_GIT;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.storage.file.FileRepository;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.GitTFConfiguration;

public final class RepositoryUtil
{
    private RepositoryUtil()
    {
    }

    /**
     * Creates a new repository
     * 
     * @param repositoryPath
     *        repository path
     * @param bare
     *        is bare
     * 
     * @return
     * @throws IOException
     */
    public static FileRepository createNewRepository(final String repositoryPath, final boolean bare)
        throws IOException
    {
        Check.notNull(repositoryPath, "repositoryPath"); //$NON-NLS-1$

        final File repositoryDirectory;
        final File workingDirectory;

        if (bare)
        {
            workingDirectory = null;
            repositoryDirectory = new File(repositoryPath);
        }
        else
        {
            workingDirectory = new File(repositoryPath);
            repositoryDirectory = new File(workingDirectory, DOT_GIT);
        }

        final File directoryToValidate = bare ? repositoryDirectory : workingDirectory;
        if (directoryToValidate.exists() && directoryToValidate.isFile())
        {
            throw new IOException(Messages.formatString("RepositoryUtil.IsNotADirectoryFormat", //$NON-NLS-1$
                directoryToValidate.getAbsolutePath()));
        }

        if (!bare && workingDirectory.exists() && workingDirectory.listFiles().length != 0)
        {
            throw new IOException(Messages.formatString("RepositoryUtil.DirectoryNotEmptyFormat", //$NON-NLS-1$
                workingDirectory.getAbsolutePath()));
        }

        if (repositoryDirectory.exists() && repositoryDirectory.listFiles().length != 0)
        {
            throw new IOException(Messages.formatString("RepositoryUtil.DirectoryNotEmptyFormat", //$NON-NLS-1$
                repositoryDirectory.getAbsolutePath()));
        }

        return new FileRepository(repositoryDirectory);
    }

    /**
     * Creates a repository object in the specified directory
     * 
     * @param gitDir
     * @return
     * @throws IOException
     */
    public static Repository findRepository(final String gitDir)
        throws IOException
    {
        RepositoryBuilder repoBuilder =
            new RepositoryBuilder().setGitDir(gitDir != null ? new File(gitDir) : null).readEnvironment().findGitDir();

        boolean isBare = false;

        if (repoBuilder.getGitDir() == null)
        {
            isBare = true;
            repoBuilder.setGitDir(new File(".")); //$NON-NLS-1$
        }

        Repository repository = repoBuilder.build();

        if (isBare)
        {
            /*
             * if this is a bare repo we need to check if it has the
             * configuration
             */
            GitTFConfiguration config = GitTFConfiguration.loadFrom(repository);
            if (config == null)
            {
                return null;
            }
        }

        if (!repository.getDirectory().exists() || !repository.getDirectory().isDirectory())
        {
            String directoryName = repository.getDirectory().toString();
            throw new IOException(Messages.formatString("RepositoryUtil.NotGitRepoExceptionFormat", directoryName)); //$NON-NLS-1$
        }

        return repoBuilder.build();
    }
}
