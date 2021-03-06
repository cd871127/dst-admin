package com.tugos.dst.admin.service;


import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import com.google.common.collect.Range;
import com.tugos.dst.admin.enums.StartTypeEnum;
import com.tugos.dst.admin.utils.DstConfigData;
import com.tugos.dst.admin.utils.DstConstant;
import com.tugos.dst.admin.utils.FileUtils;
import com.tugos.dst.admin.utils.SystemDataUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;

/**
 * @author qinming
 * @date 2020-05-21
 * <p>
 *     核心定时器服务，定时执行更新，备份任务
 *     启动释放管理脚本
 *     启动读取存储的数据
 *     定时将缓存写入文件
 * </p>
 */
@Service
@Slf4j
public class CoreScheduleService {

    @Value("${dst.username:admin}")
    private String dstUser;

    @Value("${dst.password:123456}")
    private String dstPassword;


    private HomeService homeService;

    private ShellService shellService;

    /**
     * 最大阈值 3分钟
     * 任务时间在当前时间的0到3分钟之间执行
     */
    public int upper = 3 * 60 * 1000;

    /**
     * 每天更新清理一下定时任务的执行次数
     */
    @Scheduled(cron = "1 0 0 * * ?")
    public void resetScheduleMap() {
        Set<String> backupKeySet = DstConfigData.SCHEDULE_BACKUP_MAP.keySet();
        for (String key : backupKeySet) {
            DstConfigData.SCHEDULE_BACKUP_MAP.put(key, 0);
        }
        Set<String> updateKeySet = DstConfigData.SCHEDULE_UPDATE_MAP.keySet();
        for (String key : updateKeySet) {
            DstConfigData.SCHEDULE_UPDATE_MAP.put(key, 0);
        }
    }

    /**
     * 定时任务每5秒执行一次,第一次延长10秒
     */
    @Scheduled(fixedDelay = 5*1000, initialDelay = 10*1000)
    public void scheduleExe() {
        this.backupGame();
        this.updateGame();
        //将数据存储到文件中
        SystemDataUtils.saveDataToFile();
    }


    /**
     * 更新游戏任务
     */
    public void updateGame(){
        Date currentDate = new Date();
        String currentDateStr = DateUtil.format(currentDate, DatePattern.NORM_DATE_PATTERN);
        Set<String> updateListTime = DstConfigData.SCHEDULE_UPDATE_MAP.keySet();
        if (CollectionUtils.isNotEmpty(updateListTime)) {
            updateListTime.forEach(time -> {
                Integer count = DstConfigData.SCHEDULE_UPDATE_MAP.get(time);
                if (count < 1) {
                    DateTime parse = DateUtil.parse(currentDateStr + " " + time, DatePattern.NORM_DATETIME_PATTERN);
                    long execTime = parse.getTime();
                    long currentDateTime = currentDate.getTime();
                    long subTime = currentDateTime - execTime;
                    if (Range.open(0, upper).contains((int) subTime)){
                        log.info("定时更新并重启游戏");
                        homeService.updateGame();
                        homeService.start(StartTypeEnum.START_ALL.type);
                        //记录执行次数
                        DstConfigData.SCHEDULE_UPDATE_MAP.put(time,1);
                    }
                }
            });
        }
    }


    /**
     * 备份游戏任务
     */
    public void backupGame(){
        Date currentDate = new Date();
        String currentDateStr = DateUtil.format(currentDate, DatePattern.NORM_DATE_PATTERN);
        Set<String> backupListTime = DstConfigData.SCHEDULE_BACKUP_MAP.keySet();
        //执行备份任务
        if (CollectionUtils.isNotEmpty(backupListTime)) {
            backupListTime.forEach(time -> {
                Integer count = DstConfigData.SCHEDULE_BACKUP_MAP.get(time);
                if (count < 1) {
                    DateTime parse = DateUtil.parse(currentDateStr + " " + time, DatePattern.NORM_DATETIME_PATTERN);
                    long execTime = parse.getTime();
                    long currentDateTime = currentDate.getTime();
                    long subTime = currentDateTime - execTime;
                    if (Range.open(0, upper).contains((int) subTime)){
                        log.info("定时备份游戏");
                        String weekStr = DateUtil.thisDayOfWeekEnum().toChinese();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
                        String format = sdf.format(new Date());
                        format += weekStr;
                        String fileName = format + "_sys.tar";
                        shellService.createBackup(fileName);
                        DstConfigData.SCHEDULE_BACKUP_MAP.put(time,1);
                    }
                }
            });
        }

    }


    /**
     * 启动时释放安装dst游戏脚本
     * 读取文件中存储的数据到缓存中
     */
    @PostConstruct
    public void initSystem() throws Exception {
        String data = SystemDataUtils.readProjectData(DstConstant.DST_ADMIN_JSON);
        if (StringUtils.isNotBlank(data)) {
            //本地有数据读取到缓存中
            SystemDataUtils.readDataToCache(data);
        } else {
            //配置每天6点更新游戏
            DstConfigData.SCHEDULE_UPDATE_MAP.put("06:00:00", 0);
            //每天6点，18点备份
            DstConfigData.SCHEDULE_BACKUP_MAP.put("06:00:00", 0);
            DstConfigData.SCHEDULE_BACKUP_MAP.put("18:00:00", 0);
            DstConfigData.USER_INFO.setUsername(dstUser);
            DstConfigData.USER_INFO.setPassword(dstPassword);
        }
        //释放脚本并授权
        copyAndChmod(DstConstant.INSTALL_DST);
        copyAndChmod(DstConstant.DST_START);
    }

    /**
     * 释放脚本并授权
     */
    private void copyAndChmod(String fileName) throws Exception {
        boolean copy = FileUtils.fileShellCopy(fileName);
        if (copy) {
            FileUtils.chmod(fileName);
        }
    }

    @Autowired
    public void setHomeService(HomeService homeService) {
        this.homeService = homeService;
    }

    @Autowired
    public void setShellService(ShellService shellService) {
        this.shellService = shellService;
    }
}
