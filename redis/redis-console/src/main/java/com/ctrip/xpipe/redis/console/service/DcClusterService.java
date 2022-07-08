package com.ctrip.xpipe.redis.console.service;

import com.ctrip.xpipe.redis.console.controller.api.data.meta.DcClusterCreateInfo;
import com.ctrip.xpipe.redis.console.model.ClusterTbl;
import com.ctrip.xpipe.redis.console.model.DcClusterModel;
import com.ctrip.xpipe.redis.console.model.DcClusterTbl;

import java.util.List;

public interface DcClusterService {
	DcClusterTbl find(long dcId, long clusterId);
	DcClusterTbl find(String dcName, String clusterName);
	DcClusterCreateInfo findDcClusterCreateInfo(final String dcName, final String clusterName);
	void updateDcCluster(DcClusterCreateInfo dcClusterCreateInfo);
	List<DcClusterTbl> findAllDcClusters();
	DcClusterTbl addDcCluster(String dcName, String clusterName);
    void updateDcClustersByDcClusterModels(List<DcClusterModel> dcClusterModels, ClusterTbl clusterTbl);
    DcClusterTbl addDcCluster(String dcName, String clusterName, String redisConfigRule);
	List<DcClusterTbl> findByClusterIds(List<Long> clusterIds);
	List<DcClusterTbl> findAllByDcId(long dcId);
	List<DcClusterTbl> findClusterRelated(long clusterId);
	List<DcClusterCreateInfo> findClusterRelated(String clusterName);
	DcClusterModel findDcClusterModelByClusterAndDc(String clusterName, String dcName);
	List<DcClusterModel> findDcClusterModelsByCluster(String clusterName);
}
