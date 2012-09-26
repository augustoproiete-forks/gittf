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
import java.util.Collection;

import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
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

    public static ObjectId getCurrentBranchHeadCommitID(final Repository repository)
        throws Exception
    {
        return getCommitId(repository, Constants.HEAD);
    }

    public static ObjectId getMasterHeadCommitID(final Repository repository)
        throws Exception
    {
        return getCommitId(repository, Constants.R_HEADS + Constants.MASTER);
    }

    public static final ObjectId expandAbbreviatedId(final Repository repository, final AbbreviatedObjectId objectID)
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(objectID, "objectID"); //$NON-NLS-1$

        if (repository != null)
        {
            ObjectReader objReader = repository.getObjectDatabase().newReader();

            try
            {
                Collection<ObjectId> objects = objReader.resolve(objectID);

                if (objects.size() == 0)
                {
                    return null;
                }
                else if (objects.size() == 1)
                {
                    return objects.iterator().next();
                }
                else
                {
                    throw new RuntimeException(Messages.formatString("RepositoryUtil.AmbiguousObjectFormat", objectID)); //$NON-NLS-1$
                }
            }
            catch (IOException exception)
            {
                throw new RuntimeException(exception);
            }
            finally
            {
                if (objReader != null)
                {
                    objReader.release();
                }
            }
        }

        return null;
    }

    private static ObjectId getCommitId(final Repository repository, String name)
        throws Exception
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$

        /*
         * Determine the HEAD commit. Ensure that it is parented off the commit
         * from the latest changeset.
         */
        Ref ref = repository.getRef(name);

        if (ref == null)
        {
            throw new Exception(Messages.getString("RepositoryUtil.NoHeadRef")); //$NON-NLS-1$
        }

        ObjectId commitId = ref.getObjectId();

        if (commitId == null)
        {
            throw new Exception(Messages.getString("RepositoryUtil.NoCommitForHead")); //$NON-NLS-1$
        }

        return commitId;
    }
}
