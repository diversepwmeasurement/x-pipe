package com.ctrip.xpipe.redis.core.redis.operation.parser;

import com.ctrip.xpipe.redis.core.redis.operation.*;
import com.ctrip.xpipe.redis.core.redis.operation.op.RedisOpMset;
import com.ctrip.xpipe.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author lishanglin
 * date 2022/2/18
 */
@Component
public class RedisOpMsetParser extends AbstractRedisOpParser implements RedisOpParser {

    @Autowired
    public RedisOpMsetParser(RedisOpParserManager redisOpParserManager) {
        redisOpParserManager.registerParser(RedisOpType.MSET, this);
    }

    @Override
    public RedisOp parse(byte[][] args) {
        if (0 == args.length % 2) throw new IllegalArgumentException("wrong number of arguments for MSET");
        List<Pair<RedisKey, byte[]>> kvs = new ArrayList<>();

        for (int i = 1; i < args.length; i+=2) {
            RedisKey key = new RedisKey(args[i]);
            byte[] value = args[i + 1];
            kvs.add(Pair.of(key, value));
        }

        return new RedisOpMset(args, kvs);
    }

    @Override
    public int getOrder() {
        return 0;
    }

}
