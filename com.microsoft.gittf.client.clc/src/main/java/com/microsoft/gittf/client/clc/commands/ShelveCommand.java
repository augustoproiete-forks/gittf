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

package com.microsoft.gittf.client.clc.commands;

import org.eclipse.jgit.lib.ObjectId;

import com.microsoft.gittf.client.clc.Console.Verbosity;
import com.microsoft.gittf.client.clc.ExitCode;
import com.microsoft.gittf.client.clc.Messages;
import com.microsoft.gittf.client.clc.arguments.Argument;
import com.microsoft.gittf.client.clc.arguments.ArgumentOptions;
import com.microsoft.gittf.client.clc.arguments.ChoiceArgument;
import com.microsoft.gittf.client.clc.arguments.FreeArgument;
import com.microsoft.gittf.client.clc.arguments.SwitchArgument;
import com.microsoft.gittf.client.clc.arguments.ValueArgument;
import com.microsoft.gittf.client.clc.commands.framework.CommandTaskExecutor;
import com.microsoft.gittf.core.tasks.ShelveDifferenceTask;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.RepositoryUtil;

public class ShelveCommand
    extends PendingChangesCommand
{
    public static final String COMMAND_NAME = "shelve"; //$NON-NLS-1$

    private static Argument[] ARGUMENTS = new Argument[]
    {
        new SwitchArgument("help", Messages.getString("Command.Argument.Help.HelpText")), //$NON-NLS-1$ //$NON-NLS-2$

        new ChoiceArgument(Messages.getString("Command.Argument.Display.HelpText"), //$NON-NLS-1$
            new SwitchArgument("quiet", //$NON-NLS-1$
                'q',
                Messages.getString("Command.Argument.Quiet.HelpText")), //$NON-NLS-1$

            new SwitchArgument("verbose", //$NON-NLS-1$
                Messages.getString("Command.Argument.Verbose.HelpText")) //$NON-NLS-1$
        ),

        new SwitchArgument("replace", Messages.getString("ShelveCommand.Argument.Replace.HelpText")), //$NON-NLS-1$ //$NON-NLS-2$

        new ValueArgument("resolve", //$NON-NLS-1$
            Messages.getString("PendingChangesCommand.Argument.Resolve.ValueDescription"), //$NON-NLS-1$
            Messages.getString("PendingChangesCommand.Argument.Resolve.HelpText"), //$NON-NLS-1$
            ArgumentOptions.VALUE_REQUIRED.combine(ArgumentOptions.MULTIPLE)),

        new ValueArgument("associate", //$NON-NLS-1$
            Messages.getString("PendingChangesCommand.Argument.Associate.ValueDescription"), //$NON-NLS-1$
            Messages.getString("PendingChangesCommand.Argument.Associate.HelpText"), //$NON-NLS-1$
            ArgumentOptions.VALUE_REQUIRED.combine(ArgumentOptions.MULTIPLE)),

        new FreeArgument("name", Messages.getString("ShelveCommand.Argument.Name.HelpText"), ArgumentOptions.REQUIRED) //$NON-NLS-1$ //$NON-NLS-2$
        };

    @Override
    protected String getCommandName()
    {
        return COMMAND_NAME;
    }

    @Override
    public Argument[] getPossibleArguments()
    {
        return ARGUMENTS;
    }

    @Override
    public String getHelpDescription()
    {
        return Messages.getString("ShelveCommand.HelpDescription"); //$NON-NLS-1$
    }

    @Override
    public int run()
        throws Exception
    {
        verifyGitTfConfigured();
        verifyRepoSafeState();

        final String name = ((FreeArgument) getArguments().getArgument("name")).getValue(); //$NON-NLS-1$

        final ObjectId headCommitId = RepositoryUtil.getHeadCommitID(getRepository());

        final ShelveDifferenceTask shelveTask =
            new ShelveDifferenceTask(
                getRepository(),
                headCommitId,
                getVersionControlClient(),
                getServerConfiguration().getServerPath(),
                name);

        shelveTask.setWorkItemCheckinInfo(getWorkItemCheckinInfo());
        shelveTask.setReplaceExistingShelveset(getArguments().contains("replace")); //$NON-NLS-1$

        final TaskStatus shelveStatus = new CommandTaskExecutor(getProgressMonitor()).execute(shelveTask);

        if (shelveStatus.isOK() && shelveStatus.getCode() == ShelveDifferenceTask.NO_CHANGES)
        {
            getConsole().getOutputStream(Verbosity.NORMAL).println(Messages.getString("ShelveCommand.NoChanges")); //$NON-NLS-1$
        }

        return shelveStatus.isOK() ? ExitCode.SUCCESS : ExitCode.FAILURE;
    }
}
