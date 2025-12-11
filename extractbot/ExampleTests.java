package extractbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import extractbot.tool.TestUtils;


public class ExampleTests {

	////////////
	/** TODO
	 *	Jifeng: Change the path of "pathFile" to your local path.
	 */
        private String pathFile = "/workspace/omit-codepush/extractbot/Example.java";
	////////////
	
	private String methodName1 = "example1";
	private String methodName2 = "example2";
	
	/** TODO
	 * 	Jifeng: Write down the source code of your own MyExtractor. 
	 */
	private MyExtractor myExtractor = new MyExtractor();
	
	/* A matrix (a 2D-array) of a control flow graph. Each row is an edge; each node is labeled with its ID. */
	private int[][] matrixCfg = new int[][] {
		{0, 3}, {3, 4}, {4, 5}, {5, 4}, {4, 1}, {1, 7}, {7, 8}, {8, 9}, {9, 8}, {8, 2}
	};
	
	/* A matrix (a 2D-array) of prime paths. Each row is one prime path; each node is labeled with its ID. */
	private int[][] matrixPrimePath = new int[][] {
		{4, 5, 4}, 
		{5, 4, 5}, 
		{8, 9, 8}, 
		{9, 8, 9}, 
		{9, 8, 2}, 
		{5, 4, 1, 7, 8, 2}, 
		{0, 3, 4, 1, 7, 8, 2}, 
		{0, 3, 4, 5}, 
		{5, 4, 1, 7, 8, 9}, 
		{0, 3, 4, 1, 7, 8, 9}
	};
	
	/**
	 * 	Jifeng: You do not need to update the following-up code. 
	 * 	This test method is used to check whether the control flow graph is correctly extracted. 
	 */
	@Test
	public void testControlFlowGraph()
	{
		int[][] source = myExtractor.getControlFlowGraphInArray(pathFile, methodName1);
		assertTrue(TestUtils.checkControlFlowGraph(source, matrixCfg));
	}
	
	/**
	 * 	Jifeng: You do not need to update the following-up code. 
	 * 	This test method is used to check whether the prime paths are correctly extracted. 
	 */
	@Test
	public void testPrimePaths()
	{
		int[][] source = myExtractor.getTestRequirementsInArray(pathFile, methodName1);
		assertTrue(TestUtils.checkTestRequirements(source, matrixPrimePath));
	}
	
	/**
	 * 	Jifeng: You do not need to update the following-up code. 
	 * 	This test method is used to check whether the test paths are correctly extracted. 
	 */
	@Test
	public void testTestPaths()
	{
		int nodeStart = 0;
		int nodeEnd = 2;
		int[][] source = myExtractor.getTestPathsInArray(pathFile, methodName1);
		
		assertEquals(TestUtils.checkTestPaths(source, matrixPrimePath, matrixCfg, nodeStart, nodeEnd), 0);
	}
}
