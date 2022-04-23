package com.ctrip.xpipe.redis.console.service.impl;

import com.ctrip.xpipe.api.email.EmailService;
import com.ctrip.xpipe.cluster.ClusterType;
import com.ctrip.xpipe.redis.console.annotation.DalTransaction;
import com.ctrip.xpipe.redis.console.config.ConsoleConfig;
import com.ctrip.xpipe.redis.console.constant.XPipeConsoleConstant;
import com.ctrip.xpipe.redis.console.dao.ClusterDao;
import com.ctrip.xpipe.redis.console.dao.RouteDao;
import com.ctrip.xpipe.redis.console.exception.BadRequestException;
import com.ctrip.xpipe.redis.console.exception.ServerException;
import com.ctrip.xpipe.redis.console.migration.status.ClusterStatus;
import com.ctrip.xpipe.redis.console.migration.status.MigrationStatus;
import com.ctrip.xpipe.redis.console.model.*;
import com.ctrip.xpipe.redis.console.model.consoleportal.ClusterListUnhealthyClusterModel;
import com.ctrip.xpipe.redis.console.model.consoleportal.ProxyChainModel;
import com.ctrip.xpipe.redis.console.model.consoleportal.RouteInfoModel;
import com.ctrip.xpipe.redis.console.model.consoleportal.UnhealthyInfoModel;
import com.ctrip.xpipe.redis.console.notifier.ClusterMetaModifiedNotifier;
import com.ctrip.xpipe.redis.console.notifier.cluster.ClusterDeleteEventFactory;
import com.ctrip.xpipe.redis.console.notifier.cluster.ClusterEvent;
import com.ctrip.xpipe.redis.console.proxy.ProxyChain;
import com.ctrip.xpipe.redis.console.query.DalQuery;
import com.ctrip.xpipe.redis.console.sentinel.SentinelBalanceService;
import com.ctrip.xpipe.redis.console.service.*;
import com.ctrip.xpipe.redis.console.util.DataModifiedTimeGenerator;
import com.ctrip.xpipe.redis.core.entity.*;
import com.ctrip.xpipe.redis.core.meta.MetaCache;
import com.ctrip.xpipe.utils.MapUtils;
import com.ctrip.xpipe.utils.ObjectUtils;
import com.ctrip.xpipe.utils.StringUtil;
import com.ctrip.xpipe.utils.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.unidal.dal.jdbc.DalException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ClusterServiceImpl extends AbstractConsoleService<ClusterTblDao> implements ClusterService {

	@Autowired
	private DcService dcService;
	@Autowired
	private ClusterDao clusterDao;
	@Autowired
	private RouteDao routeDao;
	@Autowired
	private ClusterMetaModifiedNotifier notifier;
	@Autowired
	private ShardService shardService;
	@Autowired
	private OrganizationService organizationService;
	@Autowired
	private DcClusterShardService dcClusterShardService;
	@Autowired
	private DelayService delayService;
	@Autowired
	private RouteService routeService;
	@Autowired
	private ProxyService proxyService;
	@Autowired
	private MetaCache metaCache;

	@Autowired
	private ClusterDeleteEventFactory clusterDeleteEventFactory;

	@Autowired
	private DcClusterService dcClusterService;

	@Autowired
	private RedisService redisService;

	@Autowired
	private SentinelBalanceService sentinelBalanceService;

	@Autowired
	private ConsoleConfig consoleConfig;

	private static final String SPLITTER = "-";
	private static final String PROXYTCP = "PROXYTCP://%s:80";
	private static final String PROXYTLS = "PROXYTLS://%s:443";

	private Random random = new Random();

	@Override
	public ClusterTbl find(final String clusterName) {
		return clusterDao.findClusterByClusterName(clusterName);
	}

	@Override
	public List<ClusterTbl> findAllByNames(List<String> clusterNames) {
		return clusterDao.findClustersWithName(clusterNames);
	}

	@Override
	public ClusterStatus clusterStatus(String clusterName) {

		ClusterTbl clusterTbl = find(clusterName);
		if(clusterTbl == null){
			throw new IllegalArgumentException("cluster not found:" + clusterName);
		}
		return ClusterStatus.valueOf(clusterTbl.getStatus());
	}

	@Override
	public List<DcTbl> getClusterRelatedDcs(String clusterName) {
		ClusterTbl clusterTbl = find(clusterName);
		if (null == clusterTbl) {
			throw new IllegalArgumentException("cluster not found:" + clusterName);
		}

		List<DcClusterTbl> dcClusterTbls = dcClusterService.findClusterRelated(clusterTbl.getId());
		List<DcTbl> result = Lists.newLinkedList();
		for(DcClusterTbl dcClusterTbl : dcClusterTbls) {
			result.add(dcService.find(dcClusterTbl.getDcId()));
		}
		return result;
	}

	@Override
	public ClusterTbl find(final long clusterId) {
		return queryHandler.handleQuery(new DalQuery<ClusterTbl>() {
			@Override
			public ClusterTbl doQuery() throws DalException {
				return dao.findByPK(clusterId, ClusterTblEntity.READSET_FULL);
			}

		});
	}


	@Override
	public List<String> findAllClusterNames() {

		List<ClusterTbl> clusterTbls = queryHandler.handleQuery(new DalQuery<List<ClusterTbl>>() {
			@Override
			public List<ClusterTbl> doQuery() throws DalException {
				return dao.findAllClusters(ClusterTblEntity.READSET_NAME);
			}
		});

		List<String> clusterNames = new ArrayList<>(clusterTbls.size());

		clusterTbls.forEach( clusterTbl -> clusterNames.add(clusterTbl.getClusterName()));

		return clusterNames;
	}

	@Override
	public Map<String, Long> getAllCountByActiveDc() {
		List<DcTbl> dcs = dcService.findAllDcs();
		Map<String, Long> counts = new HashMap<>();

		dcs.forEach(dcTbl -> {
			counts.put(dcTbl.getDcName(), getCountByActiveDc(dcTbl.getId()));
		});

		return counts;
	}

	@Override
	public Long getCountByActiveDc(long activeDc) {
		return queryHandler.handleQuery(new DalQuery<Long>() {
			@Override
			public Long doQuery() throws DalException {
				return dao.countByActiveDc(activeDc, ClusterTblEntity.READSET_COUNT).getCount();
			}
		});
	}

	@Override
	public Long getAllCount() {
		return queryHandler.handleQuery(new DalQuery<Long>() {
			@Override
			public Long doQuery() throws DalException {
				return dao.totalCount(ClusterTblEntity.READSET_COUNT).getCount();
			}
		});
	}

	@Override
	@DalTransaction
	public synchronized ClusterTbl createCluster(ClusterModel clusterModel) {
		ClusterTbl cluster = clusterModel.getClusterTbl();
		List<DcTbl> allDcs = clusterModel.getDcs();
		List<ShardModel> shards = clusterModel.getShards();
		ClusterType clusterType = ClusterType.lookup(cluster.getClusterType());

		// ensure active dc assigned
		if(!clusterType.supportMultiActiveDC() && XPipeConsoleConstant.NO_ACTIVE_DC_TAG == cluster.getActivedcId()) {
			throw new BadRequestException("No active dc assigned.");
		}
		ClusterTbl proto = dao.createLocal();
		proto.setClusterName(cluster.getClusterName().trim());
		proto.setClusterType(cluster.getClusterType());
		proto.setClusterDescription(cluster.getClusterDescription());
		proto.setStatus(ClusterStatus.Normal.toString());
		proto.setIsXpipeInterested(true);
		proto.setClusterLastModifiedTime(DataModifiedTimeGenerator.generateModifiedTime());
		proto.setClusterDesignatedRouteIds(cluster.getClusterDesignatedRouteIds() == null ? "" : cluster.getClusterDesignatedRouteIds());

		if (clusterType.supportMultiActiveDC()) {
			proto.setActivedcId(0L);
		} else {
			proto.setActivedcId(cluster.getActivedcId());
		}
		if(!checkEmails(cluster.getClusterAdminEmails())) {
			throw new IllegalArgumentException("Emails should be ctrip emails and separated by comma or semicolon");
		}
		proto.setClusterAdminEmails(cluster.getClusterAdminEmails());
		proto.setClusterOrgId(getOrgIdFromClusterOrgName(cluster));

		final ClusterTbl queryProto = proto;
		ClusterTbl result =  queryHandler.handleQuery(new DalQuery<ClusterTbl>(){
			@Override
			public ClusterTbl doQuery() throws DalException {
				return clusterDao.createCluster(queryProto);
			}
		});

		if(allDcs != null){
			for(DcTbl dc : allDcs) {
				// single active dc cluster bind active dc when create
				if (!clusterType.supportMultiActiveDC() && dc.getId() == cluster.getActivedcId()) continue;
				bindDc(cluster.getClusterName(), dc.getDcName());
			}
		}

		if(shards != null){
			for (ShardModel shard : shards) {
				shardService.createShard(cluster.getClusterName(), shard.getShardTbl(), shard.getSentinels());
			}
		}

		return result;
	}

	public long getOrgIdFromClusterOrgName(ClusterTbl cluster) {
		String orgName = cluster.getClusterOrgName();
		OrganizationTbl organizationTbl = organizationService.getOrgByName(orgName);
		if(organizationTbl == null)
			return 0L;
		Long id = organizationTbl.getId();
		return id == null ? 0L : id;
	}

	@Override
	public ClusterTbl findClusterAndOrg(String clusterName) {
		ClusterTbl clusterTbl = clusterDao.findClusterAndOrgByName(clusterName);
		OrganizationTbl organizationTbl = clusterTbl.getOrganizationInfo();
		if(organizationTbl != null) {
			clusterTbl.setClusterOrgName(organizationTbl.getOrgName());
		}
		// Set null if no organization bind with cluster
		if(organizationTbl == null || organizationTbl.getId() == null) {
			clusterTbl.setOrganizationInfo(null);
		}
		return clusterTbl;
	}

	@Override
	public Map<String, Long> getMigratableClustersCountByActiveDc() {
		List<DcTbl> dcs = dcService.findAllDcs();
		Map<String, Long> counts = new HashMap<>();

		dcs.forEach(dcTbl -> {
			counts.put(dcTbl.getDcName(), getMigratableClustersCountByActiveDcId(dcTbl.getId()));
		});

		return counts;
	}

	public long getMigratableClustersCountByActiveDcId(long activeDc) {
		List<ClusterTbl> dcClusters = findAllClustersByActiveDcId(activeDc);
		int count = 0;
		for (ClusterTbl clusterTbl : dcClusters) {
			if (ClusterType.lookup(clusterTbl.getClusterType()).supportMigration())
				count++;
		}
		return count;
	}

	public List<ClusterTbl> findAllClustersByActiveDcId(long activeDc) {
		return clusterDao.findClustersByActiveDcId(activeDc);
	}

	@Override
	public List<ClusterTbl> findClustersWithOrgInfoByActiveDcId(long activeDc) {
		List<ClusterTbl> result = clusterDao.findClustersWithOrgInfoByActiveDcId(activeDc);
		result = fillClusterOrgName(result);
		return setOrgNullIfNoOrgIdExsits(result);
	}

	@Override public List<ClusterTbl> findAllClustersWithOrgInfo() {
		List<ClusterTbl> result = clusterDao.findAllClusterWithOrgInfo();
		result = fillClusterOrgName(result);
		return setOrgNullIfNoOrgIdExsits(result);
	}

	@Override public List<ClusterTbl> findClustersWithOrgInfoByClusterType(String clusterType) {
		List<ClusterTbl> result = clusterDao.findClusterWithOrgInfoByClusterType(clusterType);
		result = fillClusterOrgName(result);
		return setOrgNullIfNoOrgIdExsits(result);
	}

	private List<ClusterTbl> fillClusterOrgName(List<ClusterTbl> clusterTblList) {
		for(ClusterTbl cluster : clusterTblList) {
			cluster.setClusterOrgName(cluster.getOrganizationInfo().getOrgName());
		}
		return clusterTblList;
	}

	private List<ClusterTbl> setOrgNullIfNoOrgIdExsits(List<ClusterTbl> clusterTblList) {
		for(ClusterTbl cluster : clusterTblList) {
			OrganizationTbl organizationTbl = cluster.getOrganizationInfo();
			if(organizationTbl.getId() == null) {
				cluster.setOrganizationInfo(null);
			}
		}
		return clusterTblList;
	}

	@Override
	public void updateCluster(String clusterName, ClusterTbl cluster) {
		ClusterTbl proto = find(clusterName);
		if(null == proto) throw new BadRequestException("Cannot find cluster");

		if(proto.getId() != cluster.getId()) {
			throw new BadRequestException("Cluster not match.");
		}
		proto.setClusterDescription(cluster.getClusterDescription());
		proto.setClusterLastModifiedTime(DataModifiedTimeGenerator.generateModifiedTime());
		if(!checkEmails(cluster.getClusterAdminEmails())) {
			throw new IllegalArgumentException("Emails should be ctrip emails and separated by comma or semicolon");
		}
		proto.setClusterAdminEmails(cluster.getClusterAdminEmails());
		proto.setClusterOrgId(getOrgIdFromClusterOrgName(cluster));
		// organization info should not be updated by cluster,
		// it's automatically updated by scheduled task
		proto.setOrganizationInfo(null);

		final ClusterTbl queryProto = proto;
		clusterDao.updateCluster(queryProto);
	}

	@Override
	public void updateActivedcId(long id, long activeDcId) {

		ClusterTbl clusterTbl = new ClusterTbl();
		clusterTbl.setId(id);
		clusterTbl.setActivedcId(activeDcId);

		queryHandler.handleUpdate(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateActivedcId(clusterTbl, ClusterTblEntity.UPDATESET_FULL);
			}
		});
	}

	@Override
	public void updateStatusById(long id, ClusterStatus clusterStatus, long migrationEventId) {

		ClusterTbl clusterTbl = new ClusterTbl();
		clusterTbl.setId(id);
		clusterTbl.setStatus(clusterStatus.toString());
		clusterTbl.setMigrationEventId(migrationEventId);

		if (clusterStatus.equals(ClusterStatus.Normal)) {
			// reset migration id when exit migration
			clusterTbl.setMigrationEventId(0);
			queryHandler.handleUpdate(new DalQuery<Integer>() {
				@Override
				public Integer doQuery() throws DalException {
					return dao.updateByPK(clusterTbl, ClusterTblEntity.UPDATESET_MIGRATION_STATUS);
				}
			});
		} else {
			queryHandler.handleUpdate(new DalQuery<Integer>() {
				@Override
				public Integer doQuery() throws DalException {
					return dao.updateStatusById(clusterTbl, ClusterTblEntity.UPDATESET_MIGRATION_STATUS);
				}
			});
		}
	}

	@Override
	public void deleteCluster(String clusterName) {
		ClusterTbl proto = find(clusterName);
		if(null == proto) throw new BadRequestException("Cannot find cluster");
		proto.setClusterLastModifiedTime(DataModifiedTimeGenerator.generateModifiedTime());
		List<DcTbl> relatedDcs = dcService.findClusterRelatedDc(clusterName);

		final ClusterTbl queryProto = proto;

		// Call cluster delete event
		ClusterEvent clusterEvent = clusterDeleteEventFactory.createClusterEvent(clusterName, proto);

		try {
			clusterDao.deleteCluster(queryProto);
		} catch (Exception e) {
			throw new ServerException(e.getMessage());
		}

		if (null != clusterEvent) clusterEvent.onEvent();

		/** Notify meta server **/
		if (consoleConfig.shouldNotifyClusterTypes().contains(queryProto.getClusterType()))
			notifier.notifyClusterDelete(clusterName, relatedDcs);
	}

	@Override
	public void bindDc(String clusterName, String dcName) {
		final ClusterTbl cluster = find(clusterName);
		final DcTbl dc = dcService.find(dcName);
		if(null == dc || null == cluster) throw new BadRequestException("Cannot bind dc due to unknown dc or cluster");

		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				ClusterType clusterType=ClusterType.lookup(cluster.getClusterType());
				if (consoleConfig.supportSentinelHealthCheck(clusterType, clusterName))
					return clusterDao.bindDc(cluster, dc, sentinelBalanceService.selectSentinel(dc.getDcName(), clusterType));
				else
					return clusterDao.bindDc(cluster, dc, null);
			}
		});
	}

	@Override
	public void unbindDc(String clusterName, String dcName) {
		final ClusterTbl cluster = find(clusterName);
		final DcTbl dc = dcService.find(dcName);
		if(null == dc || null == cluster) throw new BadRequestException("Cannot unbind dc due to unknown dc or cluster");

		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return clusterDao.unbindDc(cluster, dc);
			}
		});

		/** Notify meta server **/
		if (consoleConfig.shouldNotifyClusterTypes().contains(cluster.getClusterType()))
			notifier.notifyClusterDelete(clusterName, Arrays.asList(new DcTbl[]{dc}));

	}

	@Override
	public void update(final ClusterTbl cluster) {
		queryHandler.handleUpdate(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateByPK(cluster, ClusterTblEntity.UPDATESET_FULL);
			}
		});
	}

	@Override
	@DalTransaction
	public void exchangeName(Long formerClusterId, String formerClusterName, Long latterClusterId, String latterClusterName) {
		logger.info("[exchangeName]{}:{} <-> {}:{}", formerClusterName, formerClusterId, latterClusterName, latterClusterId);
		ClusterTbl former = queryHandler.handleQuery(new DalQuery<ClusterTbl>() {
			@Override
			public ClusterTbl doQuery() throws DalException {
				return dao.findClusterByClusterName(formerClusterName, ClusterTblEntity.READSET_FULL);
			}
		});
		ClusterTbl latter = queryHandler.handleQuery(new DalQuery<ClusterTbl>() {
			@Override
			public ClusterTbl doQuery() throws DalException {
				return dao.findClusterByClusterName(latterClusterName, ClusterTblEntity.READSET_FULL);
			}
		});

		if (former == null)  throw new BadRequestException("former cluster not found");
		if (latter == null)  throw new BadRequestException("latter cluster not found");
		if (former.getId() != formerClusterId) throw new BadRequestException("former cluster name Id not match");
		if (latter.getId() != latterClusterId) throw new BadRequestException("latter cluster name Id not match");

		String tmpClusterName = UUID.randomUUID().toString();
		former.setClusterName(tmpClusterName);
		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateByPK(former, ClusterTblEntity.UPDATESET_FULL);
			}
		});

		latter.setClusterName(formerClusterName);
		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateByPK(latter, ClusterTblEntity.UPDATESET_FULL);
			}
		});

		former.setClusterName(latterClusterName);
		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateByPK(former, ClusterTblEntity.UPDATESET_FULL);
			}
		});
	}

	public boolean checkEmails(String emails) {
		if(emails == null || emails.trim().isEmpty()) {
			return false;
		}
		String splitter = "\\s*(,|;)\\s*";
		String[] emailStrs = StringUtil.splitRemoveEmpty(splitter, emails);
		for(String email : emailStrs) {
			EmailService.CheckEmailResponse response = EmailService.DEFAULT.checkEmailAddress(email);
			if(!response.isOk())
				return false;
		}
		return true;
	}

	@Override
	public Set<String> findMigratingClusterNames() {
		List<ClusterTbl> clusterTbls = clusterDao.findMigratingClusterNames();
		return clusterTbls.stream().map(ClusterTbl::getClusterName).collect(Collectors.toSet());
	}

	@Override
	public List<ClusterTbl> findErrorMigratingClusters() {
		List<ClusterTbl> errorClusters = Lists.newArrayList();
		List<ClusterTbl> clustersWithEvents = clusterDao.findMigratingClustersWithEvents();
		for (ClusterTbl clusterWithEvent: clustersWithEvents) {
		    if (clusterWithEvent.getMigrationEvent().getId() == 0) {
				errorClusters.add(clusterWithEvent);
			}
		}
		List<ClusterTbl> overviews = clusterDao.findMigratingClustersOverview();
		for (ClusterTbl overview : overviews) {
			MigrationClusterTbl migrationClusterTbl = overview.getMigrationClusters();
			String migrationStatus = migrationClusterTbl.getStatus();
			if (migrationStatus != null) {
				String clusterStatus = overview.getStatus();
				if (MigrationStatus.valueOf(migrationStatus).getClusterStatus().toString().equals(clusterStatus)) {
					continue;
				}
			}
			overview.setMigrationEvent(null);
			overview.setMigrationClusters(null);
			errorClusters.add(overview);
		}
		return errorClusters;
	}

	@Override
	public List<ClusterTbl> findMigratingClusters() {
		return clusterDao.findMigratingClustersOverview();
	}

	@Override
	public List<ClusterListUnhealthyClusterModel> findUnhealthyClusters() {
		try {
			XpipeMeta xpipeMeta = metaCache.getXpipeMeta();
			if(xpipeMeta == null || xpipeMeta.getDcs() == null) {
				return Collections.emptyList();
			}

			Map<String, ClusterListUnhealthyClusterModel> result = Maps.newHashMap();
			UnhealthyInfoModel unhealthyInfo = delayService.getAllUnhealthyInstance();
			UnhealthyInfoModel parallelUnhealthyInfo = delayService.getAllUnhealthyInstanceFromParallelService();
			if (null != parallelUnhealthyInfo) unhealthyInfo.merge(parallelUnhealthyInfo);

			for (String unhealthyCluster : unhealthyInfo.getUnhealthyClusterNames()) {
				ClusterListUnhealthyClusterModel cluster = new ClusterListUnhealthyClusterModel(unhealthyCluster);
				cluster.setMessages(unhealthyInfo.getUnhealthyClusterDesc(unhealthyCluster));
				cluster.setUnhealthyShardsCnt(unhealthyInfo.countUnhealthyShardByCluster(unhealthyCluster));
				cluster.setUnhealthyRedisCnt(unhealthyInfo.countUnhealthyRedisByCluster(unhealthyCluster));
				result.put(unhealthyCluster, cluster);
			}

			return richClusterInfo(result);
		} catch (Exception e) {
			logger.error("[findUnhealthyClusters]", e);
			return Collections.emptyList();
		}
	}

	@VisibleForTesting
	protected List<ClusterListUnhealthyClusterModel> richClusterInfo(Map<String, ClusterListUnhealthyClusterModel> clusters) {

		if(clusters.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> clusterNames = Lists.newArrayListWithExpectedSize(clusters.size());
		clusterNames.addAll(clusters.keySet());
		List<ClusterTbl> clusterTbls = clusterDao.findClustersWithName(clusterNames);

		List<ClusterListUnhealthyClusterModel> result = Lists.newArrayListWithExpectedSize(clusterTbls.size());

		for(ClusterTbl clusterTbl : clusterTbls) {
			ClusterListUnhealthyClusterModel cluster = clusters.get(clusterTbl.getClusterName());
			cluster.setActivedcId(clusterTbl.getActivedcId())
					.setClusterAdminEmails(clusterTbl.getClusterAdminEmails())
					.setClusterDescription(clusterTbl.getClusterDescription())
					.setClusterType(clusterTbl.getClusterType());
			result.add(cluster);
		}
		return result;
	}

	@Override
	public List<ClusterTbl> findAllClusterByDcNameBind(String dcName){
		if (StringUtil.isEmpty(dcName))
			return Collections.emptyList();

		long dcId = dcService.find(dcName).getId();

		List<ClusterTbl> result = queryHandler.handleQuery(new DalQuery<List<ClusterTbl>>() {
			@Override
			public List<ClusterTbl> doQuery() throws DalException {
				return dao.findClustersBindedByDcId(dcId, ClusterTblEntity.READSET_FULL_WITH_ORG);
			}
		});

		result = fillClusterOrgName(result);
		return setOrgNullIfNoOrgIdExsits(result);
	}

	@Override
	public List<ClusterTbl> findAllClusterByDcNameBindAndType(String dcName, String clusterType) {
		List<ClusterTbl> dcClusters = findAllClusterByDcNameBind(dcName);
		if(clusterType.isEmpty()) return dcClusters;
		return dcClusters.stream().filter(clusterTbl -> clusterTbl.getClusterType().equalsIgnoreCase(clusterType)).collect(Collectors.toList());
	}

	@Override
	public List<ClusterTbl> findActiveClustersByDcName(String dcName){
		if (StringUtil.isEmpty(dcName))
			return Collections.emptyList();

		return findClustersWithOrgInfoByActiveDcId(dcService.find(dcName).getId());
	}

	@Override
	public List<ClusterTbl> findActiveClustersByDcNameAndType(String dcName, String clusterType) {
		List<ClusterTbl> dcActiveClusters = findActiveClustersByDcName(dcName);
		if(clusterType.isEmpty()) return dcActiveClusters;
		return dcActiveClusters.stream().filter(clusterTbl -> clusterTbl.getClusterType().equalsIgnoreCase(clusterType)).collect(Collectors.toList());
	}

	@Override
	public List<ClusterTbl> findAllClustersByDcName(String dcName) {
		if (StringUtil.isEmpty(dcName))
			return Collections.emptyList();

		return clusterDao.findAllByDcId(dcService.find(dcName).getId());
	}

	@Override
	public List<ClusterTbl> findAllClusterByKeeperContainer(long keeperContainerId) {
		List<Long> clusterIds = redisService.findClusterIdsByKeeperContainer(keeperContainerId);
		if (clusterIds.isEmpty()) return Collections.emptyList();
		return queryHandler.handleQuery(new DalQuery<List<ClusterTbl>>() {
			@Override
			public List<ClusterTbl> doQuery() throws DalException {
				return dao.findClustersWithOrgInfoById(clusterIds, ClusterTblEntity.READSET_FULL_WITH_ORG);
			}
		});
	}

	@Override
	public List<Set<String>> divideClusters(int partsCnt) {
		List<ClusterTbl> allClusters = queryHandler.handleQuery(new DalQuery<List<ClusterTbl>>() {
			@Override
			public List<ClusterTbl> doQuery() throws DalException {
				return dao.findAllClusters(ClusterTblEntity.READSET_NAME);
			}
		});

		if (null == allClusters) return Collections.emptyList();

		List<Set<String>> parts = new ArrayList<>(partsCnt);
		IntStream.range(0, partsCnt).forEach(i -> parts.add(new HashSet<>()));

		allClusters.forEach(clusterTbl -> parts.get((int) (clusterTbl.getId() % partsCnt)).add(clusterTbl.getClusterName()));
		return parts;
	}

	@Override
	public List<RouteInfoModel> findClusterDefaultRoutesByDcNameAndClusterName(String srcDcName, String clusterName) {
		List<RouteInfoModel> result = new ArrayList<>();

		ClusterTbl clusterTbl = find(clusterName);
		if (null == clusterTbl) throw new BadRequestException("not exist cluster " + clusterName);

		List<String> peerDcs = getPeerDcs(clusterTbl);
		Map<String, RouteMeta> chooseRoutes = metaCache.chooseRoute(clusterName, srcDcName, peerDcs, (int)clusterTbl.getClusterOrgId(), null);
		if(chooseRoutes == null || chooseRoutes.isEmpty())  return result;

		for(RouteMeta routeMeta : chooseRoutes.values()) {
			result.add(routeService.getRouteInfoModelById(routeMeta.getId()));
		}
		logger.info("[findClusterDefaultRoutesByDcNameAndClusterName]: chooseRoute:{}", result);
		return result.stream().sorted(Comparator.comparing(RouteInfoModel::getDstDcName).thenComparing(RouteInfoModel::getId)).collect(Collectors.toList());
	}

	private List<String> getPeerDcs(ClusterTbl clusterTbl) {
		DcIdNameMapper mapper = new DcIdNameMapper.DefaultMapper(dcService);

		List<String> peerDcs = new ArrayList<>();
		if(ClusterType.lookup(clusterTbl.getClusterType()).supportMultiActiveDC()){
			List<DcClusterTbl> clusterRelated = dcClusterService.findClusterRelated(clusterTbl.getId());
			if(clusterRelated == null || clusterRelated.isEmpty()) return peerDcs;

			clusterRelated.forEach((dcClusterTbl -> peerDcs.add(mapper.getName(dcClusterTbl.getDcId()))));

		} else {
			peerDcs.add(mapper.getName(clusterTbl.getActivedcId()));
		}
		return peerDcs;
	}

	@Override
	public List<RouteInfoModel> findClusterUsedRoutesByDcNameAndClusterName(String srcDcName, String clusterName) {
		Map<String, List<ProxyChain>> proxyChains = proxyService.getProxyChains(srcDcName, clusterName);

		List<RouteInfoModel> allRoutes = routeService.getAllActiveRouteInfoModelsByTagAndSrcDcName(Route.TAG_META, srcDcName);

		Set<RouteInfoModel> result = new HashSet<>();
		for(Map.Entry<String, List<ProxyChain>> shardChains : proxyChains.entrySet()) {
			List<ProxyChain> peerChains = shardChains.getValue();
			for (ProxyChain chain: peerChains) {
				result.add(getRouteInfoModelFromProxyChainModel(allRoutes, new ProxyChainModel(chain, chain.getPeerDcId(), srcDcName)));
			}
		}

		logger.info("[findClusterUsedRoutesByDcNameAndClusterName] cluster:{}, srcDc:{}, routes:{}", clusterName, srcDcName, result);
		return result.stream().sorted(Comparator.comparing(RouteInfoModel::getDstDcName).thenComparing(RouteInfoModel::getId)).collect(Collectors.toList());
	}

	@VisibleForTesting
	protected RouteInfoModel getRouteInfoModelFromProxyChainModel(List<RouteInfoModel> allDcRoutes, ProxyChainModel proxyChainModel) {
		String srcProxy = getSrcProxy(proxyChainModel);
		String dstProxy = getDstProxy(proxyChainModel);

		for(RouteInfoModel route : allDcRoutes) {
			if(route.getSrcProxies().contains(srcProxy) && route.getDstProxies().contains(dstProxy))
				return route;
		}
		return null;
	}

	private String getSrcProxy(ProxyChainModel proxyChainModel) {
		logger.debug("[getSrcProxy] backupTunnel:{}", proxyChainModel.getBackupDcTunnel());
		String[] ips = StringUtil.splitRemoveEmpty(SPLITTER, proxyChainModel.getBackupDcTunnel().getTunnelId());
		String[] results = ips[2].substring(2, ips[2].length()).split(":");

		return String.format(PROXYTCP, results[0]);
	}

	private String getDstProxy(ProxyChainModel proxyChainModel) {
		String[] ips = StringUtil.splitRemoveEmpty(SPLITTER, proxyChainModel.getBackupDcTunnel().getTunnelId());
		if(proxyChainModel.getOptionalTunnel() != null)
			ips = StringUtil.splitRemoveEmpty(SPLITTER, proxyChainModel.getOptionalTunnel().getTunnelId());

		logger.debug("[getdstProxy] tunnel:{}", proxyChainModel.getOptionalTunnel() == null ? proxyChainModel.getBackupDcTunnel() : proxyChainModel.getOptionalTunnel());
		String[] results = ips[3].substring(3, ips[3].length()).split(":");
		return String.format(PROXYTLS, results[0]);
	}

	@Override
	public List<RouteInfoModel> findClusterDesignateRoutesByDcNameAndClusterName(String dcName, String clusterName) {
		ClusterTbl clusterTbl = find(clusterName);
		if (null == clusterTbl) throw new BadRequestException("not exist cluster " + clusterName);

		String clusterDesignatedRouteIds = clusterTbl.getClusterDesignatedRouteIds();
		if(StringUtil.isEmpty(clusterDesignatedRouteIds)) return Collections.emptyList();

		List<RouteInfoModel> result = new ArrayList<>();
		Set<String> routeIds = Sets.newHashSet(clusterDesignatedRouteIds.split(","));
		routeIds.forEach(routeId -> {;
			RouteInfoModel routeInfoModel = routeService.getRouteInfoModelById(Long.parseLong(routeId.trim()));
			if(routeInfoModel != null && dcName.equalsIgnoreCase(routeInfoModel.getSrcDcName())) {
				result.add(routeInfoModel);
			}
		});
		logger.info("[findClusterDesignateRoutesByDcNameAndClusterName]{}", result);
		return result.stream().sorted(Comparator.comparing(RouteInfoModel::getDstDcName).thenComparing(RouteInfoModel::getId)).collect(Collectors.toList());
	}

	@Override
	public void updateClusterDesignateRoutes(String clusterName, String srcDcName, List<RouteInfoModel> newDesignatedRoutes) {

		ClusterTbl clusterTbl = find(clusterName);
		if (null == clusterTbl) throw new BadRequestException("not exist cluster " + clusterName);

		String oldClusterDesignatedRouteIds = clusterTbl.getClusterDesignatedRouteIds();
		Set<String> newDesignatedRouteIds = new HashSet<>();

		if(!StringUtil.isEmpty(oldClusterDesignatedRouteIds)) {
			Set<String> oldClusterDesignatedRoutes = Sets.newHashSet(oldClusterDesignatedRouteIds.split(","));
			oldClusterDesignatedRoutes.forEach(routeId ->{
				RouteInfoModel routeInfoModel = routeService.getRouteInfoModelById(Long.valueOf(routeId.trim()));
				if(!routeInfoModel.getSrcDcName().equalsIgnoreCase(srcDcName))
					newDesignatedRouteIds.add(routeId);
			});
		}

		newDesignatedRoutes.forEach(route->{
			newDesignatedRouteIds.add(String.valueOf(route.getId()));
		});

		logger.info("[updateClusterDesignateRoute] newDesignatedRoutes:{}", newDesignatedRouteIds);

		updateClusterDesignatedRouteIds(clusterTbl.getId(), StringUtil.join(",", arg -> arg, newDesignatedRouteIds));

		ClusterType type = ClusterType.lookup(clusterTbl.getClusterType());
		if (consoleConfig.shouldNotifyClusterTypes().contains(type.name())) {
			notifier.notifyClusterUpdate(clusterName, Collections.singletonList(srcDcName));
		}
	}

	private void updateClusterDesignatedRouteIds(long clusterId, String newClusterDesignateRoutes) {
		ClusterTbl proto = new ClusterTbl();
		proto.setId(clusterId);
		proto.setClusterDesignatedRouteIds(newClusterDesignateRoutes);

		queryHandler.handleUpdate(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateClusterDesignatedRouteIds(proto, ClusterTblEntity.UPDATESET_FULL);
			}
		});
	}

	public UseWrongRouteClusterInfoModel findUseWrongRouteClusterInfos() {
		UseWrongRouteClusterInfoModel useWrongRouteClusterInfoModel = new UseWrongRouteClusterInfoModel();

		XpipeMeta xpipeMeta = metaCache.getXpipeMeta();
		if(xpipeMeta == null || xpipeMeta.getDcs() == null) {
			return useWrongRouteClusterInfoModel;
		}

		for(DcMeta dcMeta : xpipeMeta.getDcs().values()) {
			for (ClusterMeta clusterMeta : dcMeta.getClusters().values()) {
				findUseWrongRouteClusters(clusterMeta, dcMeta.getId(), dcMeta.getRoutes(), useWrongRouteClusterInfoModel);
			}
		}
		useWrongRouteClusterInfoModel.addUsedWrongRouteCluster("cluster1", "UAT-AWS", "UAT", Sets.newHashSet(1, 2), 4);
		return useWrongRouteClusterInfoModel;
	}

	private void findUseWrongRouteClusters(ClusterMeta clusterMeta, String srcDcName, List<RouteMeta> allDcRoutes, UseWrongRouteClusterInfoModel useWrongRouteClusterInfoModel) {
		Map<String, RouteMeta> chooseDcRoutes = getChooseRoutes(srcDcName, clusterMeta, allDcRoutes);
		if(chooseDcRoutes.isEmpty()) return;

		Map<String, Set<Integer>> usedDcRoutes = getUsedRoutes(srcDcName, clusterMeta, allDcRoutes);

		for(String dstDcName : chooseDcRoutes.keySet()) {
			if(usedDcRoutes.get(dstDcName) == null
					|| !ObjectUtils.equals(usedDcRoutes.get(dstDcName), Sets.newHashSet(chooseDcRoutes.get(dstDcName).getId()))) {
				useWrongRouteClusterInfoModel.addUsedWrongRouteCluster(clusterMeta.getId(), srcDcName, dstDcName,
						usedDcRoutes.get(dstDcName), chooseDcRoutes.get(dstDcName).getId());
			}
		}
	}

	private Map<String, Set<Integer>> getUsedRoutes(String srcDcName, ClusterMeta clusterMeta, List<RouteMeta> allDcRoutes) {
		Map<String, Set<Integer>> usedDcRoutes = new HashMap<>();
		List<String> peerDcs = getPeerDcs(clusterMeta);

		for (ShardMeta shardMeta : clusterMeta.getShards().values()) {
			for (String peerDc : peerDcs) {
				if (peerDc.equals(srcDcName)) continue;

				RouteMeta usedRoute = getShardUsedRouteByDirection(srcDcName, peerDc, clusterMeta.getId(), shardMeta.getId(), allDcRoutes);
				if(usedRoute != null)
					MapUtils.getOrCreate(usedDcRoutes, usedRoute.getDstDc(), () -> Sets.newHashSet()).add(usedRoute.getId());
			}
		}

		return usedDcRoutes;
	}

	private RouteMeta getShardUsedRouteByDirection(String backUpDc, String peerDc, String clusterName, String shardName, List<RouteMeta> allDcRoutes) {
		ProxyChain chain = proxyService.getProxyChain(backUpDc, clusterName, shardName, peerDc);
		return chain == null ? null : getRouteMetaFromProxyChainModel(allDcRoutes, new ProxyChainModel(chain, chain.getPeerDcId(), backUpDc));
	}

	private RouteMeta getRouteMetaFromProxyChainModel(List<RouteMeta> allDcRoutes, ProxyChainModel proxyChainModel) {
		String srcProxy = getSrcProxy(proxyChainModel);
		String dstProxy = getDstProxy(proxyChainModel);
		for(RouteMeta route : allDcRoutes) {
			if(route.getRouteInfo().contains(srcProxy) && route.getRouteInfo().contains(dstProxy))
				return route;
		}
		return null;
	}

	private Map<String, RouteMeta> getChooseRoutes(String backUpDcName, ClusterMeta clusterMeta, List<RouteMeta> dstDcRoutes) {
		List<String> peerDcs = getPeerDcs(clusterMeta);
		Map<String, List<RouteMeta>> clusterDesignatedRoutes = getClusterDesignatedRoutes(clusterMeta.getClusterDesignatedRouteIds(), dstDcRoutes);
		return metaCache.chooseRoute(clusterMeta.getId(), backUpDcName, peerDcs, clusterMeta.getOrgId(), clusterDesignatedRoutes);
	}

	private List<String> getPeerDcs(ClusterMeta clusterMeta) {
		if(ClusterType.lookup(clusterMeta.getType()).supportMultiActiveDC()) {
			return Lists.newArrayList(clusterMeta.getDcs().split("\\s*,\\s*"));
		} else {
			return Lists.newArrayList(clusterMeta.getActiveDc());
		}
	}

	private Map<String, List<RouteMeta>> getClusterDesignatedRoutes(String clusterDesignatedRouteIds, List<RouteMeta> routes){
		if(StringUtil.isEmpty(clusterDesignatedRouteIds)) return null;

		Map<String, List<RouteMeta>> clusterDesignatedRoutes = new ConcurrentHashMap<>();

		routes.forEach((routeMeta -> {
			if(clusterDesignatedRouteIds.contains(String.valueOf(routeMeta.getId())))
				MapUtils.getOrCreate(clusterDesignatedRoutes, routeMeta.getDstDc(), ArrayList::new).add(routeMeta);
		}));

		return clusterDesignatedRoutes;
	}

}
