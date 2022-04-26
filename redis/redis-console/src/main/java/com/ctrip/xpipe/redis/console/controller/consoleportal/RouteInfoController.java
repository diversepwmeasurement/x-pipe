package com.ctrip.xpipe.redis.console.controller.consoleportal;

import com.ctrip.xpipe.redis.checker.controller.result.RetMessage;
import com.ctrip.xpipe.redis.console.controller.AbstractConsoleController;
import com.ctrip.xpipe.redis.console.exception.BadRequestException;
import com.ctrip.xpipe.redis.console.model.consoleportal.RouteDirectionModel;
import com.ctrip.xpipe.redis.console.model.consoleportal.RouteInfoModel;
import com.ctrip.xpipe.redis.console.service.RouteService;
import com.ctrip.xpipe.redis.core.entity.Route;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping(AbstractConsoleController.CONSOLE_PREFIX)
public class RouteInfoController extends AbstractConsoleController {

    @Autowired
    private RouteService routeService;

    @RequestMapping(value = "/route/status/all", method = RequestMethod.GET)
    public List<RouteInfoModel> getAllActiveRouteInfos() {
        logger.info("[getAllActiveRouteInfos]");
        try {
            return routeService.getAllActiveRouteInfoModels();
        } catch (Throwable th) {
            logger.error("[getAllActiveRouteInfos]", th);
            return Collections.emptyList();
        }
    }

    @RequestMapping(value = "/route/id/{routeId}", method = RequestMethod.GET)
    public RouteInfoModel getRouteInfoById(@PathVariable long routeId) {
        logger.info("[getRouteInfoById] id:{}", routeId);
        try {
            return routeService.getRouteInfoModelById(routeId);
        } catch (Throwable th) {
            logger.error("[getRouteInfoById id:{}]", routeId, th);
            return null;
        }
    }

    @RequestMapping(value = "/route/src-dc-name/{srcDcName}", method = RequestMethod.GET)
    public List<RouteInfoModel> getAllActiveRoutesByTagAndSrcDcName(@PathVariable String srcDcName) {
        logger.info("[getAllActiveRoutesByTagAndSrcDcName]srcDcName:{}, tag:{}",srcDcName, Route.TAG_META);
        try {
            return  routeService.getAllActiveRouteInfoModelsByTagAndSrcDcName(Route.TAG_META, srcDcName);
        } catch (Throwable th) {
            logger.error("[getAllActiveRoutesByTagAndSrcDcName]srcDcName:{}, tag:{}",srcDcName, Route.TAG_META , th);
            return Collections.emptyList();
        }
    }

    @RequestMapping(value = "/route/tag/{tag}/direction/{srcDcName}/{dstDcName}", method = RequestMethod.GET)
    public List<RouteInfoModel> getAllActiveRoutesByTagAndDirection(@PathVariable String tag, @PathVariable String srcDcName, @PathVariable String dstDcName) {
        logger.info("[getAllActiveRoutesByTagAndDirection]srcDcName:{}, dstDcName:{}, tag:{}",srcDcName, dstDcName, tag);
        try {
            return  routeService.getAllActiveRouteInfoModelsByTagAndDirection(tag, srcDcName, dstDcName);
        } catch (Throwable th) {
            logger.error("[getAllActiveRoutesByTagAndDirection]srcDcName:{}, dstDcName:{}, tag:{}",srcDcName, dstDcName, tag, th);
            return Collections.emptyList();
        }
    }

    @RequestMapping(value = "/route/tag/{tag}", method = RequestMethod.GET)
    public List<RouteInfoModel> getAllActiveRoutesByTag(@PathVariable String tag) {
        logger.info("[getAllActiveRoutesByTag] tag:{}", tag);
        try {
            return  routeService.getAllActiveRouteInfoModelsByTag(tag);
        } catch (Throwable th) {
            logger.error("[getAllActiveRoutesByTag] tag:{}", tag, th);
            return Collections.emptyList();
        }
    }

    @RequestMapping(value = "/route/direction/tag/{tag}", method = RequestMethod.GET)
    public List<RouteDirectionModel> getAllRouteDirectionInfosByTag(@PathVariable String tag) {
        logger.info("[getAllRouteDirectionInfosByTag]：{}", tag);
        try {
            return  routeService.getAllRouteDirectionModelsByTag(tag);
        } catch (Throwable th) {
            logger.error("[getAllRouteDirectionInfosByTag] tag:{}", tag, th);
            return Collections.emptyList();
        }
    }

    @RequestMapping(value = "/route", method = RequestMethod.POST)
    public RetMessage addRoute(@RequestBody RouteInfoModel model) {
        logger.info("[addRoute] route:{}", model);
        try {
            routeService.addRoute(model);
            return RetMessage.createSuccessMessage();
        } catch (Throwable th) {
            logger.error("[addRoute] route:{}", model, th);
            return RetMessage.createFailMessage(th.getMessage());
        }
    }

    @RequestMapping(value = "/route", method = RequestMethod.DELETE)
    public RetMessage deleteRoute(@RequestBody RouteInfoModel model) {
        logger.info("[deleteRoute] {}", model);

        try {
            routeService.deleteRoute(model.getId());
            return RetMessage.createSuccessMessage();
        } catch (Throwable th) {
            logger.error("[deleteRoute] route:{}", model, th);
            return RetMessage.createFailMessage(th.getMessage());
        }
    }

    @RequestMapping(value = "/route", method = RequestMethod.PUT)
    public RetMessage updateRoute(@RequestBody RouteInfoModel model) {
        logger.info("[updateRoute] {}", model);

        try {
            routeService.updateRoute(model);
            return RetMessage.createSuccessMessage();
        } catch (Throwable th) {
            logger.error("[updateRoute] route:{}", model, th);
            return RetMessage.createFailMessage(th.getMessage());
        }
    }

    @RequestMapping(value = "/routes", method = RequestMethod.PUT)
    public RetMessage updateRoutes(@RequestBody List<RouteInfoModel> models) {
        logger.info("[updateRoutes] models:{}", models);

        try {
            if(!existPublicRouteInfoModel(models)) throw new BadRequestException("none public route in this direction");

            routeService.updateRoutes(models);
            return RetMessage.createSuccessMessage();
        } catch (Throwable th) {
            logger.error("[updateRoutes] model:{}", models, th);
            return RetMessage.createFailMessage(th.getMessage());
        }
    }

    private boolean existPublicRouteInfoModel(List<RouteInfoModel> models) {
        for(RouteInfoModel model : models) {
            if (model.isPublic()) return true;
        }
        return false;
    }
}
