/*
 * Copyright Chris2018998
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.beecp.pool;

import cn.beecp.BeeDataSourceConfig;
import cn.beecp.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static cn.beecp.pool.PoolExceptionList.*;
import static cn.beecp.pool.PoolObjectsState.*;
import static cn.beecp.util.BeecpUtil.isNullText;
import static cn.beecp.util.BeecpUtil.oclose;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.*;
import static java.util.concurrent.locks.LockSupport.*;

/**
 * JDBC Connection Pool Implementation
 *
 * @author Chris.Liao
 * @version 1.0
 */
public final class FastConnectionPool extends Thread implements ConnectionPool, ConnectionPoolJMXBean{
	private int PoolMaxSize;
	private long DefaultMaxWaitNanos;//nanoseconds
	private int ConUnCatchStateCode;
	private String ConnectionTestSQL;//select
	private int ConnectionTestTimeout;//seconds
	private long MaxLifeTime;//milliseconds
	private long ConnectionTestInterval;//milliseconds
	private ConnectionPoolHook exitHook;
	private BeeDataSourceConfig poolConfig;

	private Semaphore semaphore;
	private TransferPolicy transferPolicy;
	private ConnectionTestPolicy testPolicy;
	private ConnectionFactory connFactory;
	private final Object connArrayLock =new Object();
	private final Object connNotifyLock =new Object();
	private volatile PooledConnection[] connArray = new PooledConnection[0];
	private ConcurrentLinkedQueue<Borrower> waitQueue = new ConcurrentLinkedQueue<Borrower>();
	private ThreadLocal<WeakReference<Borrower>> threadLocal = new ThreadLocal<WeakReference<Borrower>>();
	private ScheduledFuture<?> idleCheckSchFuture = null;
	private ScheduledThreadPoolExecutor idleSchExecutor = new ScheduledThreadPoolExecutor(2);

	private int networkTimeout;
	private boolean supportValidTest=true;
	private boolean supportSchema=true;
	private boolean supportNetworkTimeout=true;
	private boolean supportQueryTimeout=true;
	private boolean supportIsValidTested=false;

	private ThreadPoolExecutor networkTimeoutExecutor = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(),
			Runtime.getRuntime().availableProcessors(),15,SECONDS, new LinkedBlockingQueue<Runnable>());

	private String poolName;
	private volatile int poolState=POOL_UNINIT;
	private volatile int createConnThreadState=THREAD_WORKING;
	private AtomicInteger needAddConnSize = new AtomicInteger(0);
	private static Logger log = LoggerFactory.getLogger(FastConnectionPool.class);
	private static AtomicInteger PoolNameIndex = new AtomicInteger(1);
	private static final int MaxTimedSpins = (Runtime.getRuntime().availableProcessors() < 2) ? 0 : 32;
	private static final AtomicIntegerFieldUpdater<PooledConnection> ConnStateUpdater = AtomicIntegerFieldUpdater.newUpdater(PooledConnection.class, "state");
	private static final AtomicReferenceFieldUpdater<Borrower, Object> BorrowerStateUpdater = AtomicReferenceFieldUpdater.newUpdater(Borrower.class, Object.class, "stateObject");
	private static final AtomicIntegerFieldUpdater<FastConnectionPool> PoolStateUpdater = AtomicIntegerFieldUpdater.newUpdater(FastConnectionPool.class, "poolState");
	private static final AtomicIntegerFieldUpdater<FastConnectionPool> CreateConnThreadStateUpdater = AtomicIntegerFieldUpdater.newUpdater(FastConnectionPool.class, "createConnThreadState");

	private static final String DESC_REMOVE_INIT="init";
	private static final String DESC_REMOVE_BAD="bad";
	private static final String DESC_REMOVE_IDLE="idle";
	private static final String DESC_REMOVE_HOLD_TIMEOUT="hold_timeout";
	private static final String DESC_REMOVE_CLOSED="closed";
	private static final String DESC_REMOVE_RESET="reset";
	private static final String DESC_REMOVE_DESTROY="destroy";

	/**
	 * initialize pool with configuration
	 *
	 * @param config
	 *            data source configuration
	 * @throws SQLException
	 *             check configuration fail or to create initiated connection
	 */
	public void init(BeeDataSourceConfig config) throws SQLException {
		if (poolState== POOL_UNINIT) {
			checkProxyClasses();
			if(config == null)throw new SQLException("Datasource configuration can't be null");
			poolConfig = config;

			poolName = !isNullText(config.getPoolName()) ? config.getPoolName():"FastPool-" + PoolNameIndex.getAndIncrement();
			log.info("BeeCP({})starting....",poolName);

			PoolMaxSize=poolConfig.getMaxActive();
			connFactory=poolConfig.getConnectionFactory();
			ConnectionTestSQL=poolConfig.getConnectionTestSQL();
			ConnectionTestTimeout=poolConfig.getConnectionTestTimeout();
			this.testPolicy= new SQLQueryTestPolicy(poolConfig.isDefaultAutoCommit());
			if(isNullText(ConnectionTestSQL))
				ConnectionTestSQL="select 1 from dual";

			DefaultMaxWaitNanos=MILLISECONDS.toNanos(poolConfig.getMaxWait());
			MaxLifeTime=poolConfig.getMaxLifeTime();
			ConnectionTestInterval=poolConfig.getConnectionTestInterval();
			createInitConnections(poolConfig.getInitialSize());

			String mode;
			if (poolConfig.isFairMode()) {
				mode = "fair";
				transferPolicy = new FairTransferPolicy();
				ConUnCatchStateCode = transferPolicy.getCheckStateCode();
			} else {
				mode = "compete";
				transferPolicy =new CompeteTransferPolicy();
				ConUnCatchStateCode = transferPolicy.getCheckStateCode();
			}

			exitHook = new ConnectionPoolHook();
			Runtime.getRuntime().addShutdownHook(exitHook);
			semaphore = new Semaphore(poolConfig.getBorrowConcurrentSize(), poolConfig.isFairMode());
			networkTimeoutExecutor.allowCoreThreadTimeOut(true);
			idleCheckSchFuture = idleSchExecutor.scheduleAtFixedRate(new Runnable() {
				public void run() {// check idle connection
					closeIdleTimeoutConnection();
				}
			},config.getIdleCheckTimeInitDelay(),config.getIdleCheckTimeInterval(), TimeUnit.MILLISECONDS);

			registerJMX();
			log.info("BeeCP({})has startup{mode:{},init size:{},max size:{},concurrent size:{},max wait:{}ms,driver:{}}",
					poolName,
					mode,
					connArray.length,
					config.getMaxActive(),
					poolConfig.getBorrowConcurrentSize(),
					poolConfig.getMaxWait(),
					poolConfig.getDriverClassName());

			poolState=POOL_NORMAL;
			this.setName("PooledConnectionAdd");
			this.start();
		} else {
			throw new SQLException("Pool has initialized");
		}
	}

	/**
	 * check some proxy classes whether exists
	 */
	private void checkProxyClasses() throws SQLException {
		try {
			ClassLoader classLoader = getClass().getClassLoader();
			Class.forName("cn.beecp.pool.ProxyConnection",false, classLoader);
			Class.forName("cn.beecp.pool.ProxyStatement", false, classLoader);
			Class.forName("cn.beecp.pool.ProxyPsStatement", false, classLoader);
			Class.forName("cn.beecp.pool.ProxyCsStatement", false, classLoader);
			Class.forName("cn.beecp.pool.ProxyDatabaseMetaData", false, classLoader);
			Class.forName("cn.beecp.pool.ProxyResultSet", false, classLoader);
		} catch (ClassNotFoundException e) {
			throw new SQLException("Jdbc proxy class missed",e);
		}
	}

	boolean isSupportValidTest() {
		return supportValidTest;
	}
	boolean isSupportSchema() {
		return supportSchema;
	}
	boolean isSupportNetworkTimeout() {
		return supportNetworkTimeout;
	}
	int getNetworkTimeout() {
		return networkTimeout;
	}
	ThreadPoolExecutor getNetworkTimeoutExecutor() {
		return networkTimeoutExecutor;
	}
	private boolean existBorrower() {
		return poolConfig.getBorrowConcurrentSize()>semaphore.availablePermits()||semaphore.hasQueuedThreads();
	}
	//create Pooled connection
	private PooledConnection createPooledConn(int connState) throws SQLException {
		synchronized (connArrayLock) {
			if (connArray.length < PoolMaxSize) {
				Connection con= connFactory.create();
				setDefaultOnRawConn(con);
				PooledConnection pConn = new PooledConnection(con,connState,this,poolConfig);// add
				PooledConnection[] arrayNew = new PooledConnection[connArray.length + 1];
				System.arraycopy(connArray, 0, arrayNew, 0, connArray.length);
				arrayNew[connArray.length] = pConn;// tail
				connArray = arrayNew;

				if(MaxLifeTime>0) {
					pConn.maxLifeTimeSchFuture=idleSchExecutor.schedule(pConn, MaxLifeTime, MILLISECONDS);
				}
				return pConn;
			}else{
				return null;
			}
		}
	}

	//remove Pooled connection
	private void removePooledConn(PooledConnection pConn,String removeType) {
		pConn.state=CONNECTION_CLOSED;
		pConn.closeRawConn();
		synchronized (connArrayLock) {
			PooledConnection[] arrayNew = new PooledConnection[connArray.length - 1];
			for (int i = 0; i < connArray.length; i++) {
				if (connArray[i] == pConn) {
					System.arraycopy(connArray, i + 1, arrayNew, i, connArray.length - i - 1);
					break;
				} else {
					arrayNew[i] = connArray[i];
				}
			}
			connArray = arrayNew;
		}
	}
	//set default attribute on raw connection
	private void setDefaultOnRawConn(Connection rawConn){
		try{
			rawConn.setAutoCommit(poolConfig.isDefaultAutoCommit());
		}catch( Throwable e) {
			log.warn("BeeCP({})failed to set default on executing 'setAutoCommit'",poolName);
		}

		try{
			rawConn.setTransactionIsolation(poolConfig.getDefaultTransactionIsolationCode());
		}catch( SQLException e) {
			log.warn("BeeCP({}))failed to set default on executing to 'setTransactionIsolation'",poolName);
		}

		try{
			rawConn.setReadOnly(poolConfig.isDefaultReadOnly());
		}catch( Throwable e){
			log.warn("BeeCP({}))failed to set default on executing to 'setReadOnly'",poolName);
		}

		if(!isNullText(poolConfig.getDefaultCatalog())){
			try{
				rawConn.setCatalog(poolConfig.getDefaultCatalog());
			}catch( Throwable e) {
				log.warn("BeeCP({}))failed to set default on executing to 'setCatalog'",poolName);
			}
		}

		//for JDK1.7 begin
		if(supportSchema&&!isNullText(poolConfig.getDefaultSchema())){//test schema
			try{
				rawConn.setSchema(poolConfig.getDefaultSchema());
			}catch(Throwable e) {
				supportSchema=false;
				log.warn("BeeCP({})driver not support 'schema'",poolName);
			}
		}

		if(supportNetworkTimeout){//test networkTimeout
			try {//set networkTimeout
				this.networkTimeout=rawConn.getNetworkTimeout();
				if(networkTimeout<=0) {
					supportNetworkTimeout=false;
					log.warn("BeeCP({})driver not support 'networkTimeout'",poolName);
				}else{
					rawConn.setNetworkTimeout(this.getNetworkTimeoutExecutor(),networkTimeout);
				}
			}catch(Throwable e) {
				supportNetworkTimeout=false;
				log.warn("BeeCP({})driver not support 'networkTimeout'",poolName);
			}
		}

		if (!this.supportIsValidTested) {//test isValid
			try {//test Connection.isValid
				if(!rawConn.isValid(ConnectionTestTimeout))
					throw new SQLException();
				this.testPolicy = new ConnValidTestPolicy();
			} catch (Throwable e) {
				supportValidTest=false;
				log.warn("BeeCP({})driver not support 'isValid'",poolName);
				Statement st=null;
				try {
					st=rawConn.createStatement();
					st.setQueryTimeout(ConnectionTestTimeout);
				}catch(Throwable ee){
					supportQueryTimeout=false;
					log.warn("BeeCP({})driver not support 'queryTimeout'",poolName);
				}finally{
					if(st!=null)oclose(st);
				}
			} finally {
				supportIsValidTested = true;
			}
		}
		//for JDK1.7 end
	}

	/**
	 * check connection state
	 *
	 * @return if the checked connection is active then return true,otherwise
	 *         false if false then close it
	 */
	private boolean testOnBorrow(PooledConnection pConn) {
		long currentTime=currentTimeMillis();
		if(pConn.isAllowBorrow)
			if(currentTime-pConn.lastAccessTime-ConnectionTestInterval<0 || testPolicy.isActive(pConn)) return true;

		removePooledConn(pConn,DESC_REMOVE_BAD);
		tryToCreateNewConnByAsyn();
		return false;
	}
	/**
	 * create initialization connections
	 *
	 * @throws SQLException
	 *             error occurred in creating connections
	 */
	private void createInitConnections(int initSize) throws SQLException {
		try {
			for (int i=0;i<initSize; i++)
				createPooledConn(CONNECTION_IDLE);
		} catch (SQLException e) {
			for (PooledConnection pConn : connArray)
				removePooledConn(pConn,DESC_REMOVE_INIT);
			throw e;
		}
	}

	/**
	 * borrow one connection from pool
	 *
	 * @return If exists idle connection in pool,then return one;if not, waiting
	 *         until other borrower release
	 * @throws SQLException
	 *             if pool is closed or waiting timeout,then throw exception
	 */
	public Connection getConnection() throws SQLException {
		if (poolState != POOL_NORMAL)throw PoolCloseException;

		WeakReference<Borrower> bRef = threadLocal.get();
		Borrower borrower=(bRef !=null)?bRef.get():null;
		if (borrower != null) {
			PooledConnection pConn=borrower.lastUsedConn;
			if (pConn != null && ConnStateUpdater.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_USING)) {
				if(testOnBorrow(pConn))return createProxyConnection(pConn, borrower);

				borrower.lastUsedConn = null;
			}
		} else {
			borrower = new Borrower();
			threadLocal.set(new WeakReference<Borrower>(borrower));
		}

		try{
			long deadline=nanoTime()+DefaultMaxWaitNanos;
			if (semaphore.tryAcquire(DefaultMaxWaitNanos,NANOSECONDS)) {
				try {
					//1:try to  search one from array
					for (PooledConnection pConn:connArray) {
						if (ConnStateUpdater.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_USING) && testOnBorrow(pConn))
							return createProxyConnection(pConn, borrower);
					}
					//2:try to create one directly
					PooledConnection pConn;
					if(connArray.length<PoolMaxSize && (pConn=createPooledConn(CONNECTION_USING))!=null)
						return createProxyConnection(pConn,borrower);

					//3:try to get one transferred connection
					long watTimeNanos;
					Object stateObject;
					int spinSize = MaxTimedSpins;
					Thread borrowThread=borrower.thread;
					borrower.stateObject=BORROWER_NORMAL;
					boolean isTimeout=false,isInterrupted=false;
					SQLException failedException=null;

					try {// wait one transferred connection
						waitQueue.offer(borrower);
						while (true){
							stateObject = borrower.stateObject;
							if (stateObject instanceof PooledConnection) {
								pConn = (PooledConnection) stateObject;
								if(transferPolicy.tryToCatch(pConn)&&testOnBorrow(pConn))
									return createProxyConnection(pConn, borrower);

								borrower.stateObject = BORROWER_NORMAL;//reset to normal
								yield();continue;
							} else if (stateObject instanceof SQLException) {
								failedException=(SQLException)stateObject;
								break;
							}

							if (isInterrupted||(isInterrupted=borrowThread.isInterrupted())) {
								if(BorrowerStateUpdater.compareAndSet(borrower, stateObject, BORROWER_INTERRUPTED))break;
								continue;
							}

							if (isTimeout||(isTimeout=(watTimeNanos=deadline-nanoTime())<=0)) {
								if (BorrowerStateUpdater.compareAndSet(borrower, stateObject, BORROWER_TIMEOUT))break;
								continue;
							}

							if (spinSize>0){spinSize--;continue;}//spin
							if (BorrowerStateUpdater.compareAndSet(borrower, stateObject, BORROWER_WAITING))
								parkNanos(borrower, watTimeNanos);
						} // while
					} finally {
						waitQueue.remove(borrower);
					}

					if(failedException!=null)throw failedException;
					if(isInterrupted)throw RequestInterruptException;
				}finally { semaphore.release();}
			}
			throw RequestTimeoutException;
		}catch(InterruptedException e){
			throw RequestInterruptException;
		}
	}

	// create proxy to wrap connection as result
	private static ProxyConnectionBase createProxyConnection(PooledConnection pConn, Borrower borrower)
			throws SQLException {
		// borrower.setBorrowedConnection(pConn);
		// return pConn.proxyConnCurInstance=new ProxyConnection(pConn);
		throw new SQLException("Proxy classes not be generated,please execute 'ProxyClassGenerator' after compile");
	}

	/**
	 * remove connection
	 *
	 * @param pConn
	 *            target connection need release
	 */
	void abandonOnReturn(PooledConnection pConn) {
		removePooledConn(pConn,DESC_REMOVE_BAD);
		tryToCreateNewConnByAsyn();
	}

	/**
	 * return connection to pool
	 *
	 * @param pConn
	 *            target connection need release
	 */
	public void recycle(PooledConnection pConn) {
		Object state;
		Borrower borrower;
		transferPolicy.beforeTransfer(pConn);
		Iterator<Borrower>iterator=waitQueue.iterator();
		while(iterator.hasNext()) {
			borrower=iterator.next();
			while(true){
				state=borrower.stateObject;
				if((state!=BORROWER_NORMAL && state!=BORROWER_WAITING))break;

				if(pConn.state != ConUnCatchStateCode)return;
				if(BorrowerStateUpdater.compareAndSet(borrower,state,pConn)) {//transfer successful
					if(state == BORROWER_WAITING) unpark(borrower.thread);
					return;
				}
			}
		}
		transferPolicy.onFailedTransfer(pConn);
	}
	/**
	 * @param exception:
	 *            transfer Exception to waiter
	 */
	private void transferException(SQLException exception) {
		Object state;
        Borrower borrower;
        Iterator<Borrower>iterator=waitQueue.iterator();
        while(iterator.hasNext()) {
            borrower=iterator.next();
			while(true){
				state=borrower.stateObject;
				if((state!=BORROWER_NORMAL && state != BORROWER_WAITING))break;
				if(BorrowerStateUpdater.compareAndSet(borrower,state,exception)) {//transfer successful
					if(state == BORROWER_WAITING) unpark(borrower.thread);
					return;
				}
			}
		}
	}

	/**
	 * inner timer will call the method to clear some idle timeout connections
	 * or dead connections,or long time not active connections in using state
	 */
	private void closeIdleTimeoutConnection() {
		if (poolState == POOL_NORMAL) {
			for (PooledConnection pConn : connArray) {
				int state = pConn.state;
				if (state == CONNECTION_IDLE && !existBorrower()) {
					boolean isTimeoutInIdle=(!pConn.isAllowBorrow || currentTimeMillis() - pConn.lastAccessTime - poolConfig.getIdleTimeout()>=0);
					if (isTimeoutInIdle && ConnStateUpdater.compareAndSet(pConn, state, CONNECTION_CLOSED)) {//need close idle
						removePooledConn(pConn, DESC_REMOVE_IDLE);
						tryToCreateNewConnByAsyn();
					}
				} else if (state == CONNECTION_USING) {
					boolean isHolTimeoutInNotUsing = ((currentTimeMillis() - pConn.lastAccessTime - poolConfig.getHoldTimeout()>= 0));
					if (isHolTimeoutInNotUsing && ConnStateUpdater.compareAndSet(pConn, state, CONNECTION_CLOSED)) {
						removePooledConn(pConn, DESC_REMOVE_HOLD_TIMEOUT);
						tryToCreateNewConnByAsyn();
					}
				} else if (state == CONNECTION_CLOSED) {
					removePooledConn(pConn, DESC_REMOVE_CLOSED);
					tryToCreateNewConnByAsyn();
				}
			}
		}
	}

	// shutdown pool
	public void shutdown() {
		long parkNanos = SECONDS.toNanos(poolConfig.getWaitTimeToClearPool());
		while (true) {
			if (PoolStateUpdater.compareAndSet(this,POOL_NORMAL,POOL_CLOSED)) {
				log.info("BeeCP({})begin to shutdown",poolName);
				removeAllConnections(poolConfig.isForceCloseConnection(),DESC_REMOVE_DESTROY);
				while (!idleCheckSchFuture.isCancelled() && !idleCheckSchFuture.isDone()) {
					idleCheckSchFuture.cancel(true);
				}

				idleSchExecutor.shutdownNow();
				networkTimeoutExecutor.shutdownNow();
				shutdownCreateConnThread();
				unregisterJMX();

				try {
					Runtime.getRuntime().removeShutdownHook(exitHook);
				} catch (Throwable e) {
					log.error("BeeCP({})failed to remove pool hook",poolName);
				}

				log.info("BeeCP({})has shutdown",poolName);
				break;
			} else if (poolState == POOL_CLOSED) {
				break;
			} else {
				parkNanos(parkNanos);// wait 3 seconds
			}
		}
	}

	// remove all connections
	private void removeAllConnections(boolean force,String source) {
		while (existBorrower()) {
			transferException(PoolCloseException);
		}

		long parkNanos = SECONDS.toNanos(poolConfig.getWaitTimeToClearPool());
		while (connArray.length > 0) {
			for (PooledConnection pConn : connArray) {
				ScheduledFuture<?> maxLifeTimeSchFuture = pConn.maxLifeTimeSchFuture;
				if (maxLifeTimeSchFuture != null){
					while (!maxLifeTimeSchFuture.isCancelled() && !maxLifeTimeSchFuture.isDone()) {
						maxLifeTimeSchFuture.cancel(true);
					}
				}

				if (ConnStateUpdater.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_CLOSED)) {
					removePooledConn(pConn,source);
				} else if (pConn.state == CONNECTION_CLOSED) {
					removePooledConn(pConn,source);
				} else if (pConn.state == CONNECTION_USING) {
					if (force) {
						if (ConnStateUpdater.compareAndSet(pConn, CONNECTION_USING, CONNECTION_CLOSED)) {
							removePooledConn(pConn,source);
						}
					} else {
						boolean isTimeout = (currentTimeMillis()-pConn.lastAccessTime-poolConfig.getHoldTimeout()>= 0);
						if (isTimeout && ConnStateUpdater.compareAndSet(pConn, CONNECTION_USING, CONNECTION_CLOSED)) {
							removePooledConn(pConn,source);
						}
					}
				}
			} // for

			if (connArray.length > 0)parkNanos(parkNanos);
		} // while
		idleSchExecutor.getQueue().clear();
	}

	/**
	 * Hook when JVM exit
	 */
	private class ConnectionPoolHook extends Thread {
		public void run() {
			FastConnectionPool.this.shutdown();
		}
	}
	// notify to create connections to pool
	private void tryToCreateNewConnByAsyn() {
		if(connArray.length+needAddConnSize.get()+1<=PoolMaxSize) {
			synchronized(connNotifyLock){
				if(connArray.length+needAddConnSize.get()+1<=PoolMaxSize)  {
					needAddConnSize.incrementAndGet();
					if(CreateConnThreadStateUpdater.compareAndSet(this, THREAD_WAITING, THREAD_WORKING))
						unpark(this);
				}
			}
		}
	}
	// exit connection creation thread
	private void shutdownCreateConnThread() {
		int curSts;
		while (true) {
			curSts=createConnThreadState;
			if ((curSts==THREAD_WORKING||curSts==THREAD_WAITING )&&CreateConnThreadStateUpdater.compareAndSet(this,curSts,THREAD_DEAD)) {
				if(curSts==THREAD_WAITING)unpark(this);
				break;
			}
		}
	}

	// create connection to pool
	public void run() {
		PooledConnection pConn;
		while(true) {
			while(needAddConnSize.get() > 0) {
				needAddConnSize.decrementAndGet();
				if (!waitQueue.isEmpty()) {
					try {
						if ((pConn = createPooledConn(CONNECTION_USING)) != null)
							new TransferThread(pConn).start();
					} catch (SQLException e) {
						new TransferThread(e).start();
					}
				}
			}

			if (needAddConnSize.get()==0 && CreateConnThreadStateUpdater.compareAndSet(this, THREAD_WORKING, THREAD_WAITING))
				park(this);
			if (createConnThreadState == THREAD_DEAD) break;
		}
	}
	//new connection TransferThread
	class TransferThread extends Thread {
		private boolean isConn;
		private SQLException e;
		private PooledConnection pConn;
		TransferThread(SQLException e){this.e=e;isConn=false;}
		TransferThread(PooledConnection pConn){this.pConn=pConn;isConn=true;}
		public void run(){
			if(isConn) {
				recycle(pConn);
			}else {
				transferException(e);
			}
		}
	}
	/******************************** JMX **************************************/
	// close all connections
	public void reset() {
		reset(false);// wait borrower release connection,then close them
	}
	// close all connections
	public void reset(boolean force) {
		if (PoolStateUpdater.compareAndSet(this,POOL_NORMAL, POOL_RESTING)) {
			log.info("BeeCP({})begin to reset.",poolName);
			removeAllConnections(force,DESC_REMOVE_RESET);
			log.info("All pooledConns were cleared");
			poolState=POOL_NORMAL;// restore state;
			log.info("BeeCP({})finished reseting",poolName);
		}
	}
	public int getConnTotalSize(){
		return connArray.length;
	}
	public int getConnIdleSize(){
		int idleConnections=0;
		for (PooledConnection pConn:this.connArray) {
			if(pConn.state == CONNECTION_IDLE)
				idleConnections++;
		}
		return idleConnections;
	}
	public int getConnUsingSize(){
		int active=connArray.length - getConnIdleSize();
		return(active>0)?active:0;
	}
	public int getSemaphoreAcquiredSize(){
		return poolConfig.getBorrowConcurrentSize()-semaphore.availablePermits();
	}
	public int getSemaphoreWaitingSize(){
		return semaphore.getQueueLength();
	}
	public int getTransferWaitingSize(){
		return waitQueue.size();
	}
	// register JMX
	private void registerJMX() {
		if (poolConfig.isEnableJMX()) {
			MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
			registerJMXBean(mBeanServer,String.format("cn.beecp.pool.FastConnectionPool:type=BeeCP(%s)",poolName),this);
			registerJMXBean(mBeanServer,String.format("cn.beecp.BeeDataSourceConfig:type=BeeCP(%s)-config",poolName),poolConfig);
		}
	}
	private void registerJMXBean(MBeanServer mBeanServer,String regName,Object bean) {
		try {
			ObjectName jmxRegName = new ObjectName(regName);
			if(!mBeanServer.isRegistered(jmxRegName)) {
				mBeanServer.registerMBean(bean,jmxRegName);
			}
		} catch (Exception e) {
			log.warn("BeeCP({})failed to register jmx-bean:{}",poolName,regName,e);
		}
	}
	// unregister JMX
	private void unregisterJMX() {
		if (poolConfig.isEnableJMX()) {
			MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
			unregisterJMXBean(mBeanServer,String.format("cn.beecp.pool.FastConnectionPool:type=BeeCP(%s)",poolName));
			unregisterJMXBean(mBeanServer,String.format("cn.beecp.BeeDataSourceConfig:type=BeeCP(%s)-config",poolName));
		}
	}
	private void unregisterJMXBean(MBeanServer mBeanServer,String regName) {
		try {
			ObjectName jmxRegName = new ObjectName(regName);
			if(mBeanServer.isRegistered(jmxRegName)) {
				mBeanServer.unregisterMBean(jmxRegName);
			}
		} catch (Exception e) {
			log.warn("BeeCP({})failed to unregister jmx-bean:{}",poolName,regName,e);
		}
	}
	//******************************** JMX **************************************/

	// Connection check Policy
	interface ConnectionTestPolicy {
		boolean isActive(PooledConnection pConn);
	}
	// SQL check Policy
	class SQLQueryTestPolicy implements ConnectionTestPolicy {
		private boolean AutoCommit;
		public SQLQueryTestPolicy(boolean autoCommit){
			this.AutoCommit=autoCommit;
		}
		public boolean isActive(PooledConnection pConn){
			boolean autoCommitChged=false;
			Statement st = null;
			Connection con = pConn.rawConn;
			try {
				//may be a store procedure or a function in this test sql,so need rollback finally
				//for example: select xxx() from dual
				if(AutoCommit){
					con.setAutoCommit(false);
					autoCommitChged=true;
				}

				st = con.createStatement();
				pConn.lastAccessTime=currentTimeMillis();
				if(supportQueryTimeout){
					try {
						st.setQueryTimeout(ConnectionTestTimeout);
					}catch(Throwable e){
						log.error("BeeCP({})failed to setQueryTimeout",poolName,e);
					}
				}

				st.execute(ConnectionTestSQL);

				con.rollback();//why? maybe store procedure in test sql
				return true;
			} catch (Throwable e) {
				log.error("BeeCP({})failed to test connection",poolName,e);
				return false;
			} finally {
				if(st!=null)oclose(st);
				if(AutoCommit&& autoCommitChged){
					try {
						con.setAutoCommit(true);
					} catch (Throwable e){
						log.error("BeeCP({})failed to execute 'rollback or setAutoCommit(true)' after connection test",poolName,e);
					}
				}
			}
		}
	}
	//check Policy(call connection.isValid)
	class ConnValidTestPolicy implements ConnectionTestPolicy {
		public boolean isActive(PooledConnection pConn) {
			Connection con = pConn.rawConn;
			try {
				if(con.isValid(ConnectionTestTimeout)){
					pConn.lastAccessTime=currentTimeMillis();
					return true;
				}
			} catch (Throwable e) {
				log.error("BeeCP({})failed to test connection",poolName,e);
			}
			return false;
		}
	}
	// Transfer Policy
	interface TransferPolicy {
		int getCheckStateCode();
		void beforeTransfer(PooledConnection pConn);
		boolean tryToCatch(PooledConnection pConn);
		void onFailedTransfer(PooledConnection pConn);
	}
	class CompeteTransferPolicy implements TransferPolicy {
		public int getCheckStateCode() {return CONNECTION_IDLE;}
		public boolean tryToCatch(PooledConnection pConn) {
			return ConnStateUpdater.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_USING); }
		public void onFailedTransfer(PooledConnection pConn) { }
		public void beforeTransfer(PooledConnection pConn) {
			pConn.state=CONNECTION_IDLE;
		}
	}
	class FairTransferPolicy implements TransferPolicy {
		public int getCheckStateCode() {return CONNECTION_USING; }
		public boolean tryToCatch(PooledConnection pConn) {
			return pConn.state == CONNECTION_USING;
		}
		public void onFailedTransfer(PooledConnection pConn){pConn.state=CONNECTION_IDLE; }
		public void beforeTransfer(PooledConnection pConn) { }
	}
}
