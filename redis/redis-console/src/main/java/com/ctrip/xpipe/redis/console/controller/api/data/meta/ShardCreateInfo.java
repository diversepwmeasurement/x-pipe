package com.ctrip.xpipe.redis.console.controller.api.data.meta;

import com.ctrip.xpipe.codec.JsonCodec;
import com.ctrip.xpipe.utils.StringUtil;

/**
 * @author wenchao.meng
 *         <p>
 *         Jul 11, 2017
 */
public class ShardCreateInfo extends AbstractCreateInfo{

    protected String shardName;

    protected String shardMonitorName;

    protected String dcId;

    public ShardCreateInfo(){

    }

    public ShardCreateInfo(String shardName, String shardMonitorName){
        this(shardName, shardMonitorName, null);
    }

    public ShardCreateInfo(String shardName, String shardMonitorName, String dcId) {
        this.shardName = shardName;
        this.shardMonitorName = shardMonitorName;
        this.dcId = dcId;
    }

    public String getShardName() {
        return shardName;
    }

    public void setShardName(String shardName) {
        this.shardName = shardName;
    }

    public String getShardMonitorName() {
        return shardMonitorName;
    }

    public void setShardMonitorName(String shardMonitorName) {
        this.shardMonitorName = shardMonitorName;
    }

    public String getDcId() {
        return dcId;
    }

    public ShardCreateInfo setDcId(String dcId) {
        this.dcId = dcId;
        return this;
    }

    @Override
    public void check() throws CheckFailException{

        if(StringUtil.isEmpty(shardName)){
            throw new CheckFailException("shardName empty");
        }

        if(StringUtil.isEmpty(shardMonitorName)){
            throw new CheckFailException("shardMonitorName empty");
        }
    }

    @Override
    public String toString() {
        return JsonCodec.INSTANCE.encode(this);
    }
}
