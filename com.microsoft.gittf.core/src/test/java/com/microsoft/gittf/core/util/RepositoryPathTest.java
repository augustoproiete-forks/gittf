package com.microsoft.gittf.core.util;

import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

public class RepositoryPathTest
    extends TestCase
{
    public void testGetCommonPrefix()
    {
        /* 1 */
        String path1 = "project/folder1/folder2/folder3"; //$NON-NLS-1$
        String path2 = "project/folder1/folder2/folder3"; //$NON-NLS-1$

        List<String> differences = new ArrayList<String>(2);
        String result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals(path1, result);
        assertEquals("", differences.get(0)); //$NON-NLS-1$
        assertEquals("", differences.get(1)); //$NON-NLS-1$

        /* 2 */
        path1 = "project/folder1/folder2/foldera3"; //$NON-NLS-1$
        path2 = "project/folder1/folder2/folderb3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project/folder1/folder2", result); //$NON-NLS-1$
        assertEquals("foldera3", differences.get(0)); //$NON-NLS-1$
        assertEquals("folderb3", differences.get(1)); //$NON-NLS-1$

        /* 3 */
        path1 = "project/folder1/foldera2/folder3"; //$NON-NLS-1$
        path2 = "project/folder1/folderb2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project/folder1", result); //$NON-NLS-1$
        assertEquals("foldera2/folder3", differences.get(0)); //$NON-NLS-1$
        assertEquals("folderb2/folder3", differences.get(1)); //$NON-NLS-1$

        /* 4 */
        path1 = "project/foldera1/foldera2/folder3"; //$NON-NLS-1$
        path2 = "project/folderb1/folderb2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project", result); //$NON-NLS-1$
        assertEquals("foldera1/foldera2/folder3", differences.get(0)); //$NON-NLS-1$
        assertEquals("folderb1/folderb2/folder3", differences.get(1)); //$NON-NLS-1$

        /* 5 */
        path1 = "project1/folder1/folder2/folder3"; //$NON-NLS-1$
        path2 = "project2/folder1/folder2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("", result); //$NON-NLS-1$
        assertEquals(path1, differences.get(0));
        assertEquals(path2, differences.get(1));

        /* 6 */
        path1 = "project1/folder1"; //$NON-NLS-1$
        path2 = "project1/folder1/folder2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project1/folder1", result); //$NON-NLS-1$
        assertEquals("", differences.get(0)); //$NON-NLS-1$
        assertEquals("folder2/folder3", differences.get(1)); //$NON-NLS-1$

        /* 7 */
        path1 = "project1/folder1"; //$NON-NLS-1$
        path2 = "project1/folder1a/folder2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project1", result); //$NON-NLS-1$
        assertEquals("folder1", differences.get(0)); //$NON-NLS-1$
        assertEquals("folder1a/folder2/folder3", differences.get(1)); //$NON-NLS-1$

        /* 8 */
        path1 = ""; //$NON-NLS-1$
        path2 = "project1/folder1/folder2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("", result); //$NON-NLS-1$
        assertEquals("", differences.get(0)); //$NON-NLS-1$
        assertEquals("project1/folder1/folder2/folder3", differences.get(1)); //$NON-NLS-1$

        /* 9 */
        path1 = "project1/folder1/folder2/folder3"; //$NON-NLS-1$
        path2 = "project1/folder2/folder3"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project1", result); //$NON-NLS-1$
        assertEquals("folder1/folder2/folder3", differences.get(0)); //$NON-NLS-1$
        assertEquals("folder2/folder3", differences.get(1)); //$NON-NLS-1$

        /* 10 */
        path1 = "project1/folder1/folder2/folder3"; //$NON-NLS-1$
        path2 = "project1"; //$NON-NLS-1$

        differences = new ArrayList<String>(2);
        result = RepositoryPath.getCommonPrefix(path1, path2, differences);

        assertEquals("project1", result); //$NON-NLS-1$
        assertEquals("folder1/folder2/folder3", differences.get(0)); //$NON-NLS-1$
        assertEquals("", differences.get(1)); //$NON-NLS-1$
    }
}
