package com.microsoft.gittf.client.tfs;

import java.text.MessageFormat;

import com.microsoft.gittf.client.tfs.Library.GitTfCommand;
import com.microsoft.gittf.client.tfs.Library.Logger;

public class TfsCloneTests
    extends GitTfTestBase
{
    /**
     * Just clone a tfs database which was provided in the test configuration
     * file.
     */
    public void testGitTfClone()
    {
        if (!configured)
        {
            return;
        }

        try
        {
            GitTfCommand cmd =
                new GitTfCommand(MessageFormat.format("clone {0} $/", TestEnvironment.getCollectionUrl())); //$NON-NLS-1$
            cmd.getWorkingFolder(getWorkspaceFolder());
            cmd.run();

            Logger.log(MessageFormat.format("Command Input:  {0}", cmd.getCommandInput())); //$NON-NLS-1$
            Logger.log(MessageFormat.format("Exit:  {0}", cmd.getExitCode())); //$NON-NLS-1$
            Logger.log("Standard Output", cmd.getStandardOut()); //$NON-NLS-1$ 
            Logger.log("Standard Error", cmd.getStandardErr()); //$NON-NLS-1$

            if (cmd.getExitCode() != 0)
            {
                cmd.logDetails();
            }

            if (cmd.getStandardErr().length() != 0)
            {
                super.fail(MessageFormat.format("Expected no standard error but received: {0}", cmd.getStandardErr())); //$NON-NLS-1$
            }

            assertEquals(0, cmd.getExitCode());
        }
        catch (Throwable e)
        {
            fail(e.getMessage());
        }
    }
}
