/**
 * Elune - Lightweight Forum Powered by Razor.
 * Copyright (C) 2017, Touchumind<chinash2010@gmail.com>
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.elune.controller.api;

import com.elune.constants.CoinRewards;
import com.elune.dal.DBManager;
import com.elune.dal.RedisManager;
import com.elune.entity.UserEntity;
import com.elune.entity.UsermetaEntity;
import com.elune.model.*;
import com.elune.service.*;

import com.elune.utils.DateUtil;
import com.fedepot.exception.HttpException;
import com.fedepot.ioc.annotation.FromService;
import com.fedepot.mvc.annotation.*;
import com.fedepot.mvc.controller.APIController;
import com.fedepot.mvc.http.Session;
import com.fedepot.util.DateKit;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static com.elune.constants.UserLogType.*;
import static com.elune.constants.UserStatus.*;
import static com.elune.constants.BalanceLogType.*;

/**
 * @author Touchumind
 */
@RoutePrefix("api/v1/users")
public class UserController extends APIController {

    private DBManager dbManager;

    @FromService
    private UserMetaService userMetaService;

    @FromService
    private UserService userService;

    @FromService
    private TopicService topicService;

    @FromService
    private PostService postService;

    @FromService
    private RedisManager redisManager;

    @FromService
    private UserLogMQService userLogMQService;

    @FromService
    private BalanceMQService balanceMQService;

    public UserController(DBManager dbManager) {

        this.dbManager = dbManager;
    }

    @HttpPost
    @Route("{long:id}")
    public void getUserDetail(long id) {

        try {

            User user = userService.getUser(id);
            Succeed(user);
        } catch (Exception e) {

            Fail(e);
        }

    }

    @HttpPost
    @Route("name")
    public void getNamedUser(@FromBody NamedUserFetchModel namedUserFetchModel) {

        try {

            NamedUser namedUser = userService.getNamedUser(namedUserFetchModel.username);
            namedUser.setTopicsCount(topicService.countTopicsByAuthor(namedUser.getId()));
            namedUser.setPostsCount(postService.countPostsByAuthor(namedUser.getId()));
            namedUser.setFavoritesCount(userMetaService.countFavorites(namedUser.getId()));
            namedUser.setEmail("");

            Jedis jedis = redisManager.getJedis();
            String lastConnect = jedis.get("_session_".concat(Long.toString(namedUser.getId())));
            redisManager.retureRes(jedis);
            Integer lastConnectStamp = lastConnect != null ? Integer.valueOf(lastConnect) : 0;
            namedUser.setLastSeen(lastConnectStamp);
            namedUser.setOnline(DateUtil.getTimeStamp() - lastConnectStamp < 60 * 10);

            Succeed(namedUser);
        } catch (Exception e) {

            Fail(e);
        }
    }

    @HttpPost
    @Route("profile")
    public void updateUserProfile(@FromBody UserProfileSetting userProfileSetting) {

        Session session = Request().session();
        long uid = session == null || session.attribute("uid") == null ? 0 : session.attribute("uid");
        if (uid < 1) {

            throw new HttpException("尚未登录, 不能更新资料", 401);
        }

        UserEntity userEntity = userService.getUserEntity(uid);
        if (userEntity == null || userEntity.getStatus().equals(DELETE)) {

            throw new HttpException("用户不存在或已被删除", 404);
        }

        if (userEntity.getStatus().equals(UNACTIVE)) {

            throw new HttpException("账户尚未激活, 不能更新资料", 401);
        }

        // log
        if (!userProfileSetting.nickname.equals(userEntity.getNickname())) {
            userLogMQService.createUserLog(uid, userEntity.getUsername(), L_UPDATE_PROFILE, "Nickname: ".concat(userEntity.getNickname()), "Nickname: ".concat(userProfileSetting.nickname), "", Request().getIp(), Request().getUa());
        }
        if (!userProfileSetting.url.equals(userEntity.getUrl())) {
            userLogMQService.createUserLog(uid, userEntity.getUsername(), L_UPDATE_PROFILE, "Url: ".concat(userEntity.getUrl()), "Url: ".concat(userProfileSetting.url), "", Request().getIp(), Request().getUa());
        }
        if (!userProfileSetting.bio.equals(userEntity.getBio())) {
            userLogMQService.createUserLog(uid, userEntity.getUsername(), L_UPDATE_PROFILE, "Bio: ".concat(userEntity.getBio()), "Bio: ".concat(userProfileSetting.bio), "", Request().getIp(), Request().getUa());
        }

        try {

            Map<String, Object> updateInfo = new HashMap<>(4);
            updateInfo.put("id", uid);
            updateInfo.put("nickname", userProfileSetting.nickname);
            updateInfo.put("url", userProfileSetting.url);
            updateInfo.put("bio", userProfileSetting.bio);
            Succeed(userService.updateInfo(updateInfo));
        } catch (Exception e) {
            Fail(e);
        }
    }

    @HttpPost
    @Route("dailySign")
    public void dailySign() {

        Session session = Request().session();
        long uid = session == null || session.attribute("uid") == null ? 0 : session.attribute("uid");
        if (uid < 1) {

            throw new HttpException("尚未登录, 不能签到", 401);
        }

        try {

            if (userMetaService.hasSignedToday(uid)) {

                throw new HttpException("今日已签到", 400);
            }

            Random random = new Random(DateUtil.getTimeStamp() % 50);

            int change = random.nextInt(50) + 1;

            userMetaService.createOrUpdateUsermeta(uid, "dailySign", Integer.toString(DateUtil.getTimeStamp()));

            // balance
            balanceMQService.increaseBalance(uid, change, B_DAILY_SIGN, ("每日签到获得").concat(Integer.toString(change)).concat("铜币奖励"), "");

            Map<String, Object> resp = new HashMap<>(2);
            resp.put("msg", "签到成功, 获得 " + change + " 铜币");
            resp.put("result", change);
            Succeed(resp);
        } catch (Exception e) {

            Fail(e);
        }
    }
}
