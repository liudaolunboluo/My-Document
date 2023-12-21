# JPA引起的诡异更新事件

 ## 案发

在这周某天做需求的时候，发现数据库一个表的字段诡异的被更新了，但是代码里并没有执行过update，代码大概如下：

```java
  @Transactional(rollbackFor = Exception.class)
    public void test(Long projectId) {
        final TrainProject trainProject = trainProjectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCodeEnum.TRAIN_PROJECT_NOT_EXISTS));
        trainProject.setDatsetPath(path);
...
  
  trainProfileRepository.update(profile);
  trainfileRepository.update(file);
    }
```

这里setDatsetPath的逻辑在于基于存储的相对路径拼上绝对路径，然后后面便于直接用project对象做业务操作。

这个代码看起来没什么问题，但是一运行就发现，为什么我TrainProject对应的表的datasetPath字段变成了代码里拼接的绝对路径了？？导致其他地方报错了？我再三检查，代码里并没有对TrainProject有update的地方，那是怎么回事呢？



## JPA的实体状态

经过查阅资料之后发现，JPA的对象有四种状态：

- 瞬时状态（transient）：瞬时状态的实体就是一个普通的java对象，和持久化上下文无关联，数据库中也没有数据与之对应。
- 托管状态（persistent）：经过JPA查询出来的实体，此时该对象已经处于持久化上下文中，因此任何对于该实体的更新都会同步到数据库中。表现为 ：对 Jpa 对象进行set，但是不save，数据库也能自动更新。
- 游离状态（detached）：当事务提交后，处于托管状态的对象就转变为了游离状态。此时该对象已经不处于持久化上下文中，因此任何对于该对象的修改都不会同步到数据库中。
- 删除状态 （deleted）：对实体进行delete后，该实体对象就处于删除状态。其本质也就是一个瞬时状态的对象。

![image-20231109110803851](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20231109110803851.png)

这里我们的TrainProject明显是经过find出来的托管状态的实体，所以进行set之后会自动更新。

这里对问题倒是没什么疑问了就是因为JPA的原因，我对查询出来的实体做了set操作，jpa就自动更新了

本着打破砂锅问到底的精神，继续探究：JPA是如何实现的呢？他是怎么知道我有没有对实体做set操作呢？

和群友讨论一番之后有了争论：

![1801699495763_.pic_hd](/Users/zhangyunfan/Library/Containers/com.tencent.xinWeChat/Data/Library/Application Support/com.tencent.xinWeChat/2.0b4.0.9/d0952ad236add5ac76a41cbd569aa285/Message/MessageTemp/9e20f478899dc29eb19741386f9343c8/Image/1801699495763_.pic_hd.jpg)

![1791699495763_.pic](/Users/zhangyunfan/Library/Containers/com.tencent.xinWeChat/Data/Library/Application Support/com.tencent.xinWeChat/2.0b4.0.9/d0952ad236add5ac76a41cbd569aa285/Message/MessageTemp/9e20f478899dc29eb19741386f9343c8/Image/1791699495763_.pic.jpg)



我的想法是事务提交的时候对比新老对象，对刚刚查询结果，jpa自己会缓存一份快照，然后和提交事务的时候的新对象做对比，如果有不同则更新。但是其他人的看法是每次set就生成一次，我不太同意这种，因为涉及到了aba的问题，比如我开始的时候`setName("Tom")`然后提交之前执行`setName("Jim")`而原来的值就是Jim，那这种情况执行了set还是需要更新吗？

带着疑问我们看一下代码



## 源码探究

既然是事务提交的时候，我们就直接找到hibernate的事务类：`TransactionImpl`，然后找到commit方法，发现调用的是`JdbcResourceLocalTransactionCoordinatorImpl#commit`方法，在此方法中：

```java
				JdbcResourceLocalTransactionCoordinatorImpl.this.beforeCompletionCallback();
				jdbcResourceTransaction.commit();
				JdbcResourceLocalTransactionCoordinatorImpl.this.afterCompletionCallback( true );
```

就是简单的提交，然后提交之前和提交之后的钩子，那么我们要找的肯定是提交之前，因为update肯定是和当前事务一起提交的不太可能单独开一个事务，那就涉及到数据变化的问题了。

所以我们看第一行`beforeCompletionCallback`就行了,：

```java
transactionCoordinatorOwner.beforeTransactionCompletion();
```

这里根本执行的是`JdbcCoordinatorImpl#beforeTransactionCompletion`方法，`JdbcCoordinatorImpl`是 Hibernate 框架中的一个类，用于协调和管理 JDBC 连接和事务的执行:

```java
	@Override
	public void beforeTransactionCompletion() {
		this.owner.beforeTransactionCompletion();
		this.logicalConnection.beforeTransactionCompletion();
	}
```

这里的owner：`private transient JdbcSessionOwner owner;`这里`JdbcSessionOwner`是一个接口，表示当前会话持有者，他的类关系如下：

![image-20231109112613660](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20231109112613660.png)

我们debug之后发现，这里的实现类是`org.hibernate.internal.SessionImpl`所以我们直接看`org.hibernate.internal.SessionImpl#beforeTransactionCompletion`方法：

```java
	@Override
	public void beforeTransactionCompletion() {
		log.trace( "SessionImpl#beforeTransactionCompletion()" );
		flushBeforeTransactionCompletion();
		actionQueue.beforeTransactionCompletion();
		try {
			getInterceptor().beforeTransactionCompletion( getTransactionIfAccessible() );
		}
		catch (Throwable t) {
			log.exceptionInBeforeTransactionCompletionInterceptor( t );
		}
		super.beforeTransactionCompletion();
	}
```

这里，看代码的意思，我们应该关注`flushBeforeTransactionCompletion`因为刚刚介绍了，托管状态的实体要经过flush才会变成持久态，然后这里看字面意思就是在事务完成之前flush：

```java
	@Override
	public void flushBeforeTransactionCompletion() {
		final boolean doFlush = isTransactionFlushable()
				&& getHibernateFlushMode() != FlushMode.MANUAL;

		try {
			if ( doFlush ) {
				managedFlush();
			}
		}
		catch (RuntimeException re) {
			throw ExceptionMapperStandardImpl.INSTANCE.mapManagedFlushFailure( "error during managed flush", re, this );
		}
	}
```

这里会先判断是否需要flush，首先` isTransactionFlushable`其实就是：

```java
final TransactionStatus status = getCurrentTransaction().getStatus();
return status == TransactionStatus.ACTIVE || status == TransactionStatus.COMMITTING;
```

判断当前事务的状态是事务已开始，但尚未完成的状态或者处于已开始两阶段提交协议的第二阶段但尚未完成此阶段的事务的状态

然后要求flush的模式不能是手动模式，因为这里是自动flush嘛。

然后在`managedFlush`其实就是简单打印日志和调用`doFlush`，所以我们直接看`doFlush`：

```java
private void doFlush() {
		pulseTransactionCoordinator();
		checkTransactionNeededForUpdateOperation();

		try {
			if ( persistenceContext.getCascadeLevel() > 0 ) {
				throw new HibernateException( "Flush during cascade is dangerous" );
			}

			FlushEvent event = new FlushEvent( this );
			fastSessionServices.eventListenerGroup_FLUSH.fireEventOnEachListener( event, FlushEventListener::onFlush );
			delayedAfterCompletion();
		}
		catch ( RuntimeException e ) {
			throw getExceptionConverter().convert( e );
		}
	}
```

核心代码是`fastSessionServices.eventListenerGroup_FLUSH.fireEventOnEachListener( event, FlushEventListener::onFlush );`这里用消息总栈发送一个onFlush的消息，`FlushEventListener`是一个接口，他目前只有一个实现类：`DefaultFlushEventListener`，需要注意的是在`fireEventOnEachListener`中：

```java
@Override
public final <U> void fireEventOnEachListener(final U event, final BiConsumer<T,U> actionOnEvent) {
   final T[] ls = listeners;
   if ( ls != null ) {
      //noinspection ForLoopReplaceableByForEach
      for ( int i = 0; i < ls.length; i++ ) {
         actionOnEvent.accept( ls[i], event );
      }
   }
}
```

可以有多个`listeners`，而且会执行每个`listeners`的`onFlush`方法。这里给使用方自定义扩展提供了可能性，因为目前只有一个实现类`DefaultFlushEventListener`，所以我们直接看`DefaultFlushEventListener#onFlush`就行了：

```java
public void onFlush(FlushEvent event) throws HibernateException {
		final EventSource source = event.getSession();
		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();

		if ( persistenceContext.getNumberOfManagedEntities() > 0 ||
				persistenceContext.getCollectionEntriesSize() > 0 ) {

			try {
				source.getEventListenerManager().flushStart();

				flushEverythingToExecutions( event );
				performExecutions( source );
				postFlush( source );
				...
```

这里看字面意思，我们需要关注`flushEverythingToExecutions`，之前的代码都是做一些准备工作和收尾工作。

```java
protected void flushEverythingToExecutions(FlushEvent event) throws HibernateException {

   LOG.trace( "Flushing session" );

   EventSource session = event.getSession();

   final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
   session.getInterceptor().preFlush( persistenceContext.managedEntitiesIterator() );

   //准备实体的flush
   prepareEntityFlushes( session, persistenceContext );

  //准备集合的flush
   prepareCollectionFlushes( persistenceContext );


   persistenceContext.setFlushing( true );
   try {
     	//进行实体的flush
      int entityCount = flushEntities( event, persistenceContext );
     	//进行集合的flush
      int collectionCount = flushCollections( session, persistenceContext );

      event.setNumberOfEntitiesProcessed( entityCount );
      event.setNumberOfCollectionsProcessed( collectionCount );
   }
   finally {
      persistenceContext.setFlushing(false);
   }

   //some statistics
   logFlushResults( event );
}
```

在这方法中，我们只需要关注有注释的这四行代码即可，其他代码都是用于记录的。这里准备flush的时候主要是处理级联操作，也就是实体包实体这种联合操作，真正进行flush的就是`flushEntities`和`flushCollections`，一个用于集合一个是单个的实体，我们只看单个实体好了：

```java
private int flushEntities(final FlushEvent event, final PersistenceContext persistenceContext) throws HibernateException {

...
		//获取所有实体
		final Map.Entry<Object,EntityEntry>[] entityEntries = persistenceContext.reentrantSafeEntityEntries();
		final int count = entityEntries.length;

  	//挨个遍历所有实体，然后根据状态来进行实体的flush
		for ( Map.Entry<Object,EntityEntry> me : entityEntries ) {
			// Update the status of the object and if necessary, schedule an update
			EntityEntry entry = me.getValue();
			Status status = entry.getStatus();

			if ( status != Status.LOADING && status != Status.GONE ) {
				final FlushEntityEvent entityEvent = new FlushEntityEvent( source, me.getKey(), entry );
				//继续用消息总栈发送消息
        flushListeners.fireEventOnEachListener( entityEvent, FlushEntityEventListener::onFlushEntity );
			}
		}

		source.getActionQueue().sortActions();

		return count;
	}
```

这里还是用了消息总栈，和上文差不多，`FlushEntityEventListener`仍然只有一个实现类：`DefaultFlushEntityEventListener`所以我们直接看`DefaultFlushEntityEventListener#onFlushEntity`：

```java
	public void onFlushEntity(FlushEntityEvent event) throws HibernateException {
    //获取当前正在处理的实体对象。
		final Object entity = event.getEntity();
		final EntityEntry entry = event.getEntityEntry();
		final EventSource session = event.getSession();
		final EntityPersister persister = entry.getPersister();
		final Status status = entry.getStatus();
		final Type[] types = persister.getPropertyTypes();
		//判断实体是否需要进行脏检查
		final boolean mightBeDirty = entry.requiresDirtyCheck( entity );
		//获取实体类的属性值
		final Object[] values = getValues( entity, entry, mightBeDirty, session );

		event.setPropertyValues( values );

		//TODO: avoid this for non-new instances where mightBeDirty==false
		//为实体的集合属性（例如 Set、List 等）创建包装器（wrapper），以便在更新集合时触发脏检查和级联操作。
		boolean substitute = wrapCollections( session, persister, entity, entry.getId(), types, values );

    //核心代码，是否需要更新，也就是实体是否set，也就是实体被标记为脏（即属性发生了更改），则执行更新操作
		if ( isUpdateNecessary( event, mightBeDirty ) ) {
			substitute = scheduleUpdate( event ) || substitute;
		}
		...
```

也就是说我们这里核心需要看的怎么判断是否需要更新的就在方法：`isUpdateNecessary`中：

```java
	private boolean isUpdateNecessary(final FlushEntityEvent event, final boolean mightBeDirty) {
		final Status status = event.getEntityEntry().getStatus();
		if ( mightBeDirty || status == Status.DELETED ) {
			// compare to cached state (ignoring collections unless versioned)
			dirtyCheck( event );
			if ( isUpdateNecessary( event ) ) {
				return true;
			}
```

我们直接关注`dirtyCheck`：

```java
	protected void dirtyCheck(final FlushEntityEvent event) throws HibernateException {

    //获取快照实体也就是刚刚查询出来的实体的类型和值
		final Object entity = event.getEntity();
		final Object[] values = event.getPropertyValues();
		final SessionImplementor session = event.getSession();
		final EntityEntry entry = event.getEntityEntry();
		final EntityPersister persister = entry.getPersister();
		final Serializable id = entry.getId();
		final Object[] loadedState = entry.getLoadedState();

...

		if ( dirtyProperties == null ) {
			// Interceptor returned null, so do the dirty check ourself, if possible
			try {
				session.getEventListenerManager().dirtyCalculationStart();

				interceptorHandledDirtyCheck = false;
				// object loaded by update()
				dirtyCheckPossible = loadedState != null;
				if ( dirtyCheckPossible ) {
					// 找到当前实体和快照的不同，以此来判断是否需要更新
					dirtyProperties = persister.findDirty( values, loadedState, entity, session );
```

核心代码就是：`dirtyProperties = persister.findDirty( values, loadedState, entity, session );`

下面的调用栈比较深，笔者直接整理了一下：

```java
persister.findDirty(）
|
TypeHelper.findDirty(): 
|
properties[i].getType().isDirty( previousState[i], currentState[i], includeColumns[i], session ) )
|
AbstractStandardBasicType#isDirty-> isSame()->  
public final boolean isEqual(Object one, Object another) {
    return javaTypeDescriptor.areEqual( (T) one, (T) another );
  }
```

最后实际上就是通过两个值的类型来调用equls方法判断是否有改动。

下面的代码就根据找出的dirty属性和值，然后将实体对象的更新操作计划到 Hibernate 的更新队列中，在事务提交或刷新会话时执行更新操作。代码就不再赘述了。

所以笔者又猜对了。



## 解决方案

找到问题的原因和根源之后就是如何解决了：

方案1：根据上文的源码可以看出如果实体是只读属性的话就可以避免检查dirty了所以我们对相应的对象设置为只读就行了

- 设置Session默认只读：
  session.setDefaultReadOnly(true);
  使用此方法将整个Session的默认只读状态设置为true。这意味着在此Session中加载的所有实体都将被标记为只读，Hibernate将不会跟踪它们的更改。

- 设置特定实体为只读：
  session.setReadOnly(entity, true);
  使用此方法将指定的实体设置为只读。这意味着Hibernate将不会跟踪此实体的更改。请注意，如果在同一个会话中查询同一实体，这个标记会被保留，而不仅仅是对当前查询生效。

- 设置查询实体为只读：
  Query query = session.createQuery(“FROM MyEntity”);
  query.setReadOnly(true);
  List entities = query.list();
  使用此方法将查询结果设置为只读。这意味着从此查询中加载的实体将被标记为只读，Hibernate将不会跟踪它们的更改。

- 设置Criteria查询实体为只读：
  Criteria criteria = session.createCriteria(MyEntity.class);
  criteria.setReadOnly(true);
  List entities = criteria.list();
  使用此方法将Criteria查询结果设置为只读。这意味着从此查询中加载的实体将被标记为只读，Hibernate将不会跟踪它们的更改

- 设置事务只读：`@Transactional(readOnly = true)`

不过笔者当前事务有写的操作，所以不能使用这个方案。

方案2：使用原生sql而不用JPA的sql方言查询

这样查询出来的实体就不会被JPA托管了，但是改动有点多，作为备选吧

方案3:手动将Persistent（持久化状态）变成Detached（脱管状态）

引入 EntityManager然后对实体分离，改动太大，还不如方案2

方案4:把查询出来的entity转换为DTO或者BO等其他实体，然后对相应的实体做set操作避免修改entity。

靠谱，而且导致此问题的根源也是笔者偷懒直接操作了entity，笔者的项目其实都有严格的分层的：

```java
TrainProjectDTO.convert(trainProject)
```

最后使用了方案4完美解决问题