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

import java.io.IOException;
import java.text.MessageFormat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

import com.microsoft.gittf.core.GitTFConstants;
import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.GitTFConfiguration;

public final class CommitUtil
{
    private static final int ABBREVIATED_LENGTH = 7;

    private static final Log log = LogFactory.getLog(CommitUtil.class);

    private CommitUtil()
    {
    }

    public static final String abbreviate(final ObjectId objectID)
    {
        return abbreviate(null, objectID);
    }

    public static final String abbreviate(final Repository repository, final ObjectId objectID)
    {
        Check.notNull(objectID, "objectID"); //$NON-NLS-1$

        if (repository != null)
        {
            try
            {
                return repository.getObjectDatabase().newReader().abbreviate(objectID, ABBREVIATED_LENGTH).name();
            }
            catch (IOException e)
            {
                log.warn("Could not read object from object database", e); //$NON-NLS-1$
            }
        }

        return objectID.getName().substring(0, ABBREVIATED_LENGTH);
    }

    public static boolean tagCommit(final Repository repository, ObjectId commitID, int changesetID)
    {
        final RevWalk walker = new RevWalk(repository);
        try
        {
            GitTFConfiguration configuration = GitTFConfiguration.loadFrom(repository);

            if (!configuration.getTag())
            {
                return false;
            }

            String tagName = Messages.formatString("CreateCommitTask.TagNameFormat", //$NON-NLS-1$
                Integer.toString(changesetID));

            RevObject objectToTag = walker.lookupCommit(commitID);

            TagCommand tagCommand = new Git(repository).tag();
            tagCommand.setName(tagName);
            tagCommand.setObjectId(objectToTag);
            tagCommand.setForceUpdate(true);
            tagCommand.setTagger(new PersonIdent(GitTFConstants.GIT_TF_NAME, MessageFormat.format("{0} - {1}", //$NON-NLS-1$
                configuration.getServerURI().toString(),
                configuration.getServerPath())));

            Ref tagRef = tagCommand.call();

            if (tagRef == null || tagRef == ObjectId.zeroId())
            {
                log.warn("Failed to tag commit."); //$NON-NLS-1$
            }

            return true;
        }
        catch (Exception e)
        {
            // this is not a critical failure so we can still continue with the
            // operation even if tagging failed.

            log.error(e);

            return false;
        }
        finally
        {
            if (walker != null)
            {
                walker.release();
            }
        }
    }

}
