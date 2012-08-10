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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import com.microsoft.gittf.core.Messages;

public final class CommitWalker
{
    private CommitWalker()
    {
    }

    public static List<CommitDelta> getCommitList(
        final Repository repository,
        final ObjectId sourceCommitID,
        final ObjectId targetCommitID,
        final AbbreviatedObjectId[] ignoreCommitIDs)
        throws Exception
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(targetCommitID, "targetCommitID"); //$NON-NLS-1$

        List<CommitDelta> commitList = new ArrayList<CommitDelta>();

        RevWalk walker = new RevWalk(repository);

        try
        {
            final RevCommit headCommit = walker.parseCommit(targetCommitID);
            RevCommit currentCommit = headCommit;

            /*
             * Walk backwards from the destination commit searching for the
             * starting commit.
             */
            while (currentCommit != null && !currentCommit.getId().equals(sourceCommitID))
            {
                final RevCommit[] parents = currentCommit.getParents();
                RevCommit fromCommit = null;
                final RevCommit toCommit = currentCommit;

                /* The repository's initial commit. */
                if (parents == null || parents.length == 0)
                {
                    /*
                     * Sanity check: make sure we are in an unbridged
                     * repository.
                     */
                    if (sourceCommitID != null)
                    {
                        throw new Exception(Messages.formatString("CheckinHeadCommitTask.LatestNotInTreeFormat", //$NON-NLS-1$
                            CommitUtil.abbreviate(repository, sourceCommitID)));
                    }

                    fromCommit = null;
                }
                else if (parents.length == 1)
                {
                    for (AbbreviatedObjectId ignore : ignoreCommitIDs)
                    {
                        if (ignore.prefixCompare(parents[0].getId()) == 0)
                        {
                            throw new Exception(Messages.formatString(
                                "CheckinHeadCommitTask.CommitHasOneParentThatIsSquashedFormat", //$NON-NLS-1$
                                CommitUtil.abbreviate(repository, currentCommit),
                                CommitUtil.abbreviate(repository, parents[0].getId())));
                        }
                    }

                    fromCommit = walker.parseCommit(parents[0].getId());
                }
                else
                {
                    RevCommit possibleFrom = null;

                    /*
                     * See if one of the parents is our destination - if so,
                     * we'll simply use it. (This commit is the result of a
                     * merge with the latest changeset on the TFS server. We
                     * won't preserve history before the merge.)
                     */
                    for (RevCommit parent : currentCommit.getParents())
                    {
                        if (parent.getId().equals(sourceCommitID))
                        {
                            possibleFrom = parent;
                            break;
                        }
                    }

                    if (possibleFrom == null)
                    {
                        /*
                         * See if all the parents but one have been squashed -
                         * if so, we can follow the non-squashed parent for its
                         * history.
                         */
                        for (RevCommit parent : currentCommit.getParents())
                        {
                            boolean parentIgnored = false;

                            for (AbbreviatedObjectId ignore : ignoreCommitIDs)
                            {
                                if (ignore.prefixCompare(parent.getId()) == 0)
                                {
                                    parentIgnored = true;
                                    break;
                                }
                            }

                            /* This parent is squashed, continue */
                            if (parentIgnored)
                            {
                                continue;
                            }

                            /*
                             * This is a possible (non-squashed) avenue to
                             * follow, mark it as such and investigate other
                             * parents.
                             */
                            else if (possibleFrom == null)
                            {
                                possibleFrom = parent;
                            }

                            /*
                             * We have two non-squashed parents. We cannot
                             * reconcile history.
                             */
                            else
                            {
                                throw new Exception(Messages.formatString(
                                    "CheckinHeadCommitTask.NonLinearHistoryFormat", //$NON-NLS-1$
                                    CommitUtil.abbreviate(repository, currentCommit.getId())));
                            }
                        }
                    }

                    /* All our parents were squashed! */
                    if (possibleFrom == null)
                    {
                        throw new Exception(
                            Messages.formatString(
                                "CheckinHeadCommitTask.AllParentsSquashedFormat", CommitUtil.abbreviate(repository, currentCommit.getId()))); //$NON-NLS-1$
                    }
                    /* We only had one non-squashed parent */
                    else
                    {
                        fromCommit = walker.parseCommit(possibleFrom.getId());
                    }
                }

                commitList.add(new CommitDelta(fromCommit, toCommit));

                currentCommit = fromCommit;
            }
        }
        finally
        {
            walker.dispose();
        }

        Collections.reverse(commitList);

        return commitList;
    }

    public static List<CommitDelta> getAutoSquashedCommitList(
        final Repository repository,
        final ObjectId sourceCommitID,
        final ObjectId targetCommitID)
        throws Exception
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(targetCommitID, "targetCommitID"); //$NON-NLS-1$

        RevWalk walker = null;
        try
        {
            walker = new RevWalk(repository);

            RevCommit start = walker.parseCommit(targetCommitID);
            RevCommit end = sourceCommitID != null ? walker.parseCommit(sourceCommitID) : null;

            List<RevCommit> commitPath = detectAutoSquashedPath(walker, start, end);

            if (commitPath == null || commitPath.size() < 2)
            {
                throw new Exception(Messages.formatString("CheckinHeadCommitTask.LatestNotInTreeFormat", //$NON-NLS-1$
                    CommitUtil.abbreviate(repository, end.getId())));
            }

            List<CommitDelta> deltas = new ArrayList<CommitDelta>();

            for (int i = 0; i < commitPath.size() - 1; i++)
            {
                deltas.add(new CommitDelta(commitPath.get(i), commitPath.get(i + 1)));
            }

            return deltas;
        }
        finally
        {
            if (walker != null)
            {
                walker.dispose();
            }
        }
    }

    private static List<RevCommit> detectAutoSquashedPath(RevWalk walker, RevCommit start, RevCommit end)
        throws Exception
    {
        Check.notNull(walker, "walker"); //$NON-NLS-1$
        Check.notNull(start, "start"); //$NON-NLS-1$

        List<RevCommit> path = null;

        /*
         * We need to parse the start here since at this point only the id is
         * loaded. This is essential for the getParents method to complete
         * results.
         */
        start = walker.parseCommit(start.getId());

        // parents are sorted in order of oldest first
        RevCommit[] parents = start.getParents();

        /*
         * If start == end, we've reached our target and should stop following
         * this path. Likewise, if there are no parents and end == null, then we
         * are simply looking for the initial commit and we've found that also.
         */
        if (end == null && parents.length == 0)
        {
            path = new ArrayList<RevCommit>();
            path.add(null);
        }
        else if (end != null && start.getId().equals(end.getId()))
        {
            path = new ArrayList<RevCommit>();
        }
        else
        {
            /* Recurse last parent first */
            for (int parentIdx = parents.length - 1; parentIdx >= 0; parentIdx--)
            {
                RevCommit parentCommit = parents[parentIdx];

                path = detectAutoSquashedPath(walker, parentCommit, end);

                if (path != null)
                {
                    break;
                }
            }
        }

        /* If we found a path, add the start to the end of the path. */
        if (path != null)
        {
            path.add(start);
        }

        return path;
    }

    public static class CommitDelta
    {
        private final RevCommit fromCommit;
        private final RevCommit toCommit;

        public CommitDelta(final RevCommit fromCommit, final RevCommit toCommit)
        {
            Check.notNull(toCommit, "toCommit"); //$NON-NLS-1$

            this.fromCommit = fromCommit;
            this.toCommit = toCommit;
        }

        public RevCommit getFromCommit()
        {
            return fromCommit;
        }

        public RevCommit getToCommit()
        {
            return toCommit;
        }
    }
}
