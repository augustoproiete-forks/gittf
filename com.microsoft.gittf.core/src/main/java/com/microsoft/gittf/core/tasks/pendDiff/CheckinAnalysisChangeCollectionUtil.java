package com.microsoft.gittf.core.tasks.pendDiff;

import java.io.IOException;
import java.util.List;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;

public final class CheckinAnalysisChangeCollectionUtil
{
    private CheckinAnalysisChangeCollectionUtil()
    {

    }

    public static boolean contains(CheckinAnalysisChangeCollection analysis, String path)
        throws MissingObjectException,
            IncorrectObjectTypeException,
            CorruptObjectException,
            IOException
    {
        if (contains(analysis.getAdds(), path))
        {
            return true;
        }

        if (contains(analysis.getDeletes(), path))
        {
            return true;
        }

        if (contains(analysis.getEdits(), path))
        {
            return true;
        }

        if (contains(analysis.getRenames(), path))
        {
            return true;
        }

        return false;
    }

    public static boolean contains(List<? extends Change> changes, String path)
    {
        for (Change change : changes)
        {
            if (change.getPath().equals(path))
            {
                return true;
            }
        }

        return false;
    }

    public static Change getChange(List<? extends Change> changes, String path)
    {
        for (Change change : changes)
        {
            if (change.getPath().equals(path))
            {
                return change;
            }
        }

        return null;
    }
}
