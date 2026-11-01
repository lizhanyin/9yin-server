package com.swingfrog.summer.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 框架级虚拟线程执行器，用于承载阻塞型调用（同步RPC、JDBC查询等）。
 * 虚拟线程在阻塞点自动让出载体线程，等待期间不占用平台线程，
 * 因此可放心在此执行器上编写同步阻塞风格的代码。
 * 注意：不要把会向会话队列/单队列投递任务并同步等待其结果的逻辑
 * 放在队列线程（ServerEvent等平台线程池）上执行，否则会拖垮整个队列组。
 */
public class VirtualThreadMgr {

	private static final Logger log = LoggerFactory.getLogger(VirtualThreadMgr.class);

	private final ExecutorService executor;

	private static class SingleCase {
		public static final VirtualThreadMgr INSTANCE = new VirtualThreadMgr();
	}

	private VirtualThreadMgr() {
		executor = Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name("SummerVirtual-", 0).factory());
		log.info("virtual thread executor init");
	}

	public static VirtualThreadMgr get() {
		return SingleCase.INSTANCE;
	}

	public ExecutorService getExecutor() {
		return executor;
	}

	public void execute(Runnable runnable) {
		executor.execute(runnable);
	}

	public void shutdown() {
		log.info("virtual thread executor shutdown");
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				log.warn("virtual thread executor shutdown timeout, interrupt remaining tasks");
				executor.shutdownNow();
				executor.awaitTermination(10, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error(e.getMessage(), e);
		}
	}

}
