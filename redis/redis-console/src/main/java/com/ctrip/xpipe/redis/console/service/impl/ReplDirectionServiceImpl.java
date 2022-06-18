package com.ctrip.xpipe.redis.console.service.impl;

import com.ctrip.xpipe.redis.console.model.*;
import com.ctrip.xpipe.redis.console.query.DalQuery;
import com.ctrip.xpipe.redis.console.service.AbstractConsoleService;
import com.ctrip.xpipe.redis.console.service.ClusterService;
import com.ctrip.xpipe.redis.console.service.DcService;
import com.ctrip.xpipe.redis.console.service.ReplDirectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.unidal.dal.jdbc.DalException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ReplDirectionServiceImpl  extends AbstractConsoleService<ReplDirectionTblDao>
        implements ReplDirectionService {

    @Autowired
    ClusterService clusterService;

    @Autowired
    DcService dcService;

    @Override
    public ReplDirectionTbl findReplDirectionTblById(long id) {
        return queryHandler.handleQuery(new DalQuery<ReplDirectionTbl>() {
            @Override
            public ReplDirectionTbl doQuery() throws DalException {
                return dao.findByPK(id, ReplDirectionTblEntity.READSET_FULL);
            }
        });
    }

    @Override
    public List<ReplDirectionTbl> findAllReplDirectionTblsByCluster(long clusterId) {

        return queryHandler.handleQuery(new DalQuery<List<ReplDirectionTbl>>() {
            @Override
            public List<ReplDirectionTbl> doQuery() throws DalException {
                return dao.findReplDirectionsByCluster(clusterId, ReplDirectionTblEntity.READSET_FULL);
            }
        });
    }

    @Override
    public ReplDirectionInfoModel findReplDirectionInfoModelByClusterAndSrcToDc(String clusterName, String srcDcName, String toDcName) {
        ClusterTbl clusterTbl = clusterService.find(clusterName);
        if (clusterTbl == null) {
            throw new IllegalArgumentException(String.format("cluster %s does not exist", clusterName));
        }

        DcTbl srcDcTbl = dcService.find(srcDcName);
        DcTbl toDcTbl = dcService.find(toDcName);
        if (srcDcTbl == null || toDcTbl == null) {
            throw new IllegalArgumentException(String.format("src dc %s or to dc %s does not exist", srcDcName, toDcName));
        }

        ReplDirectionTbl replDirectionTbl = queryHandler.handleQuery(new DalQuery<ReplDirectionTbl>() {
            @Override
            public ReplDirectionTbl doQuery() throws DalException {
                return dao.findReplDirectionByClusterAndSrcToDc(clusterTbl.getId(), srcDcTbl.getId(), toDcTbl.getId(), ReplDirectionTblEntity.READSET_FULL);
            }
        });
        if (null == replDirectionTbl)  return null;
        return convertReplDirectionTblToReplDirectionInfoModel(replDirectionTbl, dcService.dcNameMap());
    }

    @Override
    public List<ReplDirectionInfoModel> findReplDirectionInfoModelsByClusterAndToDc(String clusterName, String toDcName) {
        ClusterTbl cluster = clusterService.find(clusterName);
        if (cluster == null) {
            throw new IllegalArgumentException(String.format("cluster %s does not exist", clusterName));
        }

        DcTbl dc = dcService.find(toDcName);
        if (dc == null) {
            throw new IllegalArgumentException(String.format("dc %s does not exist", toDcName));
        }

        List<ReplDirectionTbl> replDirectionTbls = queryHandler.handleQuery(new DalQuery<List<ReplDirectionTbl>>() {
            @Override
            public List<ReplDirectionTbl> doQuery() throws DalException {
                return dao.findReplDirectionsByClusterAndToDc(cluster.getId(), dc.getId(), ReplDirectionTblEntity.READSET_FULL);
            }
        });

        Map<Long, String> dcNameMap = dcService.dcNameMap();
        List<ReplDirectionInfoModel> result = new ArrayList<>();
        for (ReplDirectionTbl replDirectionTbl : replDirectionTbls) {
            result.add(convertReplDirectionTblToReplDirectionInfoModel(replDirectionTbl, dcNameMap));
        }

        return result;
    }

    private ReplDirectionInfoModel convertReplDirectionTblToReplDirectionInfoModel(ReplDirectionTbl replDirectionTbl,
                                                                                   Map<Long, String> dcNameMap) {
        ReplDirectionInfoModel replDirectionInfoModel = new ReplDirectionInfoModel();
        replDirectionInfoModel.setId(replDirectionTbl.getId())
                .setClusterName(clusterService.find(replDirectionTbl.getClusterId()).getClusterName())
                .setSrcDcName(dcNameMap.get(replDirectionTbl.getSrcDcId()))
                .setFromDcName(dcNameMap.get(replDirectionTbl.getFromDcId()))
                .setToDcName(dcNameMap.get(replDirectionTbl.getToDcId()));

        ClusterTbl clusterTbl = clusterService.find(replDirectionTbl.getClusterId());
        if (null == clusterTbl)
            throw new IllegalArgumentException(String.format("cluster %d does not exist", replDirectionTbl.getClusterId()));

        replDirectionInfoModel.setClusterName(clusterTbl.getClusterName());
        return replDirectionInfoModel;
    }
}
