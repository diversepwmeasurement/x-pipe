package com.ctrip.xpipe.redis.console.service;

import com.ctrip.xpipe.redis.console.model.ReplDirectionInfoModel;
import com.ctrip.xpipe.redis.console.model.ReplDirectionTbl;

import java.util.List;

public interface ReplDirectionService {

    ReplDirectionTbl findReplDirectionTblById(long id);

    List<ReplDirectionTbl> findAllReplDirectionTblsByCluster(long clusterId);

    ReplDirectionInfoModel findReplDirectionInfoModelByClusterAndSrcToDc(String clusterName,
                                                                         String srcDcName, String toDcName);

    List<ReplDirectionInfoModel> findReplDirectionInfoModelsByClusterAndToDc(String cluterName, String toDcName);
}
