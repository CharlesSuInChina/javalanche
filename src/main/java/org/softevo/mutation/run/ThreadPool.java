package org.softevo.mutation.run;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.softevo.mutation.io.Io;
import org.softevo.mutation.properties.MutationProperties;
import org.softevo.mutation.results.persistence.HibernateUtil;
import org.softevo.mutation.results.persistence.QueryManager;
import org.softevo.mutation.testsuite.RunResult;

/**
 * Class executes several instances of the mutation test tool in parallel using
 * the underlying thread pool.
 *
 * @author David Schuler
 *
 */
public class ThreadPool {

	private static Logger logger = Logger.getLogger(ThreadPool.class);

	/**
	 * Time interval when the processes are checked.
	 */
	private static final int CHECK_PERIOD = 20;

	/**
	 * Directory where the processes are executed
	 */
	private static final String EXEC_DIR = "/scratch/schuler/mutationTest/src/scripts/";

	/**
	 * Number of parallel running threads.
	 */
	private static final int NUMBER_OF_THREADS = 3;

	/**
	 * Number of mutations that are fetched randomly from the database.
	 */
	private static final int MAX_MUTATIONS = 20000;

	/**
	 * Number of tasks that will be submitted to the thread pool.
	 */
	private static final int NUMBER_OF_TASKS = 200;

	private static final int MUTATIONS_PER_TASK = 150;

	private static final String RESULT_DIR = MutationProperties.CONFIG_DIR
			+ "/result/";

	static {
		File resultDir = new File(RESULT_DIR);
		if (!resultDir.exists()) {
			resultDir.mkdir();
		}
	}

	// private static final String TASK_NAME = "test-no-compile";

	/**
	 * Command that is used to execute on mutation task.
	 */
	private static final String MUTATION_COMMAND = "/scratch/schuler/mutationTest/src/scripts/threaded-run-tests.sh";

	/**
	 * Maximum running time for one sub process.
	 */
	private static final long MAX_TIME_FOR_SUB_PROCESS = 10 * 60 * 1000;

	/**
	 * Processes that are added to the thread pool per turn. after one turn the
	 * ids of mutations without results are refreshed.
	 */
	private static final int PROCESSES_PER_TURN = 10;

	private final ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors
			.newFixedThreadPool(NUMBER_OF_THREADS);

	/**
	 * List of mutaionIds that have no result and are covered by a unit test.
	 */
	private List<Long> mutationIDs;

	/**
	 * All processes that where added to the thread pool.
	 */
	private List<ProcessWrapper> processes = new ArrayList<ProcessWrapper>();

	private int processCounter;

	public static void main(String[] args) {
		ThreadPool tp = new ThreadPool();
		tp.startTimed();
	}

	private void startTimed() {
		long mutationResultsPre = QueryManager.getNumberOfMutationsWithResult();
		long startTime = System.currentTimeMillis();
		logger.info("Start fetching mutations");
		refreshMutations();
		// mutationIDs = getFakeList();
		long fetchTime = System.currentTimeMillis();
		logger.info("Fetched " + mutationIDs.size() + " mutations in "
				+ formatMilliseconds(fetchTime - startTime));
		start();
		long duration = System.currentTimeMillis() - fetchTime;
		long actuallMutationsInDb = QueryManager
				.getNumberOfMutationsWithResult()
				- mutationResultsPre;
		logger.info("Tried to run " + MUTATIONS_PER_TASK * NUMBER_OF_TASKS
				+ " mutations - got " + actuallMutationsInDb
				+ "  results\nRun for: " + formatMilliseconds(duration));
	}

	private String formatMilliseconds(long duration) {
		long minutes = duration / 60000;
		int seconds = (int) ((duration % 60000) / 1000);
		return minutes + "'" + seconds + "''";
	}

	private void start() {
		runTasks();
	}

	private void runTasks() {
		addProcesses(PROCESSES_PER_TURN);
		while (!pool.isTerminated()) {
			try {
				boolean processesFinished = pool.awaitTermination(CHECK_PERIOD,
						TimeUnit.SECONDS);
				handleProcesses();
				logger.info("Processes finished:" + processesFinished);
				if (processesFinished) {
					pool.shutdown();
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		handleResults(processes);
	}

	/**
	 * Creates and adds the given number of processes to the ThreadPool.
	 *
	 * @param numberOfProcesses
	 *            Number of processes the should be added.
	 */
	private void addProcesses(int numberOfProcesses) {
		logger.info(String.format("Adding %d  processes", numberOfProcesses));
		for (int i = 0; i < numberOfProcesses; i++) {
			createProcess();
		}
	}

	/**
	 * Creates a new process and adds it to the ThreadPool.
	 */
	private void createProcess() {
		processCounter++;
		String outputFile = String.format(RESULT_DIR
				+ "/process-output-%02d.txt", processCounter);
		List<Long> list = getMutionIDs(MUTATIONS_PER_TASK);
		File taskIdFile = writeListToFile(list, processCounter);
		String resultFile = String.format(RESULT_DIR
				+ "/process-result-%02d.xml", processCounter);
		ProcessWrapper ps = new ProcessWrapper(MUTATION_COMMAND,
				new String[] { String.format("-D%s=%s",
						MutationProperties.MUTATION_FILE_KEY, taskIdFile
								.getAbsolutePath()) }, new File(EXEC_DIR),
				new File(outputFile), new File(resultFile));
		processes.add(ps);
		logger.info("Process: " + ps.toString());
		pool.submit(ps);
	}

	/**
	 * This method is called in regular intervals. It destroys processes that
	 * run for two long and logs statistics.
	 *
	 * @param processes
	 */
	private void handleProcesses() {
		int processesFinished = getNumberOfFinishedProcesses();
		int processesRunning = handleRunningProcess();
		logger.info(processesFinished + " processes are finished and "
				+ processesRunning + " are running");
		if (processesRunning < NUMBER_OF_THREADS
				&& NUMBER_OF_TASKS > processCounter) {
			logger.info("Adding new processes");
			refreshMutations();
			addProcesses(Math.min(PROCESSES_PER_TURN, NUMBER_OF_TASKS
					- processCounter));
		}
		if (processesRunning == 0 && processesFinished == NUMBER_OF_TASKS) {
			pool.shutdown();
		}
		handleResults(processes);
	}

	private int handleRunningProcess() {
		int processesRunning = 0;
		for (ProcessWrapper ps : processes) {
			if (ps.isRunning()) {
				processesRunning++;
				long timeRunning = ps.getTimeRunnning();
				logger.info("Process is running for "
						+ formatMilliseconds(timeRunning) + " out of "
						+ formatMilliseconds(MAX_TIME_FOR_SUB_PROCESS));
				if (timeRunning >= MAX_TIME_FOR_SUB_PROCESS) {
					logger.info("Destroying process" + ps
							+ " because time running exceeded limit: "
							+ formatMilliseconds(timeRunning));
					boolean couldBeRemoved = pool.remove(ps);
					logger.info(couldBeRemoved ? "Task was removed"
							: " Task could not be removed");
					ps.destroyProcess();
				}
			}

		}
		return processesRunning;
	}

	private int getNumberOfFinishedProcesses() {
		int processesFinished = 0;
		for (ProcessWrapper ps : processes) {
			if (ps.isFinished()) {
				processesFinished++;
			}
		}
		return processesFinished;
	}

	private void handleResults(List<ProcessWrapper> processes) {
		int totalMutations = 0;
		for (ProcessWrapper ps : processes) {
			RunResult runResult = ps.getRunResult();
			if (ps.isFinished() && runResult != null) {
				totalMutations += runResult.getMutations();
			}
		}
		logger.info(totalMutations + " mutation where actually executed");
	}

	private File writeListToFile(List<Long> list, int id) {
		String filename = String.format(RESULT_DIR + "mutation-task-%02d.txt",
				id);
		File resultFile = new File(filename);
		StringBuilder sb = new StringBuilder();
		for (Long l : list) {
			sb.append(l);
			sb.append("\n");
		}
		Io.writeFile(sb.toString(), resultFile);
		return resultFile;
	}

	private List<Long> getFakeList() {
		List<Long> list = new ArrayList<Long>();
		for (long i = 0; i < 40000; i++) {
			list.add(i);
		}
		return list;
	}

	private List<Long> getMutionIDs(int numberOfIds) {
		List<Long> list = new ArrayList<Long>();
		Random r = new Random();
		for (int i = 0; i < numberOfIds; i++) {
			if (mutationIDs.size() > 0) {
				int position = r.nextInt(mutationIDs.size());
				list.add(mutationIDs.remove(position));
			} else {
				logger.info("Not enough mutations fetched from db");
				break;
			}
		}
		return list;
	}

	private void refreshMutations() {
		mutationIDs = getMutationsIdListFromDb();
	}

	private static List<Long> getMutationsIdListFromDb() {
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction tx = session.beginTransaction();
		Query query = session
				.createSQLQuery("SELECT m.id FROM Mutation m JOIN TestCoverageClassResult tccr ON m.classname = tccr.classname JOIN TestCoverageClassResult_TestCoverageLineResult AS class_line ON class_line.testcoverageclassresult_id = tccr.id JOIN TestCoverageLineResult AS tclr ON tclr.id = class_line.lineresults_id 	WHERE m.mutationresult_id IS NULL AND m.linenumber = tclr.linenumber");
		query.setMaxResults(MAX_MUTATIONS);
		List results = query.list();
		List<Long> idList = new ArrayList<Long>();
		for (Object id : results) {
			idList.add(Long.valueOf(id.toString()));
		}
		tx.commit();
		session.close();
		return idList;
	}
}
