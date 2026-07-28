-- Run this script against an existing smart_campus_life database.

-- ----------------------------
-- Campus scenario seed normalization
-- Applies the campus-life demo data to both fresh imports and existing databases.
-- ----------------------------
START TRANSACTION;

UPDATE `tb_shop_type` SET `name` = CASE `id`
  WHEN 1 THEN '校园餐饮'
  WHEN 2 THEN '校园娱乐'
  WHEN 3 THEN '学习自习'
  WHEN 4 THEN '运动健身'
  WHEN 5 THEN '生活服务'
  WHEN 6 THEN '校园健康'
  WHEN 7 THEN '社团活动'
  WHEN 8 THEN '咖啡轻食'
  WHEN 9 THEN '校园便利'
  WHEN 10 THEN '文印文创'
  ELSE `name`
END;
UPDATE `tb_shop_type` SET `icon` = CASE `id`
  WHEN 1 THEN '/types/campus/food.svg'
  WHEN 2 THEN '/types/campus/entertainment.svg'
  WHEN 3 THEN '/types/campus/study.svg'
  WHEN 4 THEN '/types/campus/sports.svg'
  WHEN 5 THEN '/types/campus/service.svg'
  WHEN 6 THEN '/types/campus/health.svg'
  WHEN 7 THEN '/types/campus/club.svg'
  WHEN 8 THEN '/types/campus/coffee.svg'
  WHEN 9 THEN '/types/campus/convenience.svg'
  WHEN 10 THEN '/types/campus/print.svg'
  ELSE `icon`
END;


UPDATE `tb_shop`
SET `type_id` = CASE `id`
      WHEN 1 THEN 1 WHEN 2 THEN 1 WHEN 3 THEN 1
      WHEN 4 THEN 8 WHEN 5 THEN 8
      WHEN 6 THEN 5 WHEN 7 THEN 10 WHEN 8 THEN 9
      WHEN 9 THEN 3 WHEN 10 THEN 4 WHEN 11 THEN 6
      WHEN 12 THEN 7 WHEN 13 THEN 2 WHEN 14 THEN 2
      ELSE `type_id`
    END,
    `images` = CASE `id`
      WHEN 1 THEN '/imgs/campus/cafeteria-meal.png'
      WHEN 2 THEN '/imgs/campus/grilled-rice.png'
      WHEN 3 THEN '/imgs/campus/light-meal.png'
      WHEN 4 THEN '/imgs/campus/coffee-study.png'
      WHEN 5 THEN '/imgs/campus/milk-tea.png'
      WHEN 6 THEN '/imgs/campus/laundry-service.png'
      WHEN 7 THEN '/imgs/campus/service-print.png'
      WHEN 8 THEN '/imgs/campus/convenience-store.png'
      WHEN 9 THEN '/imgs/campus/study-room.png'
      WHEN 10 THEN '/imgs/campus/sports-health.png'
      WHEN 11 THEN '/imgs/campus/campus-health.png'
      WHEN 12 THEN '/imgs/campus/club-activity.png'
      WHEN 13 THEN '/imgs/campus/club-boardgame.png'
      WHEN 14 THEN '/imgs/campus/esports-room.png'
      ELSE `images`
    END,
    `name` = CASE `id`
      WHEN 1 THEN '一食堂风味窗口'
      WHEN 2 THEN '北苑烤肉饭'
      WHEN 3 THEN '校园轻食实验室'
      WHEN 4 THEN '图书馆咖啡角'
      WHEN 5 THEN '学府奶茶铺'
      WHEN 6 THEN '南门洗衣服务站'
      WHEN 7 THEN '校园文印中心'
      WHEN 8 THEN '生活区便利店'
      WHEN 9 THEN '知行自习空间'
      WHEN 10 THEN '校园运动馆'
      WHEN 11 THEN '校医院健康咨询'
      WHEN 12 THEN '社团活动中心'
      WHEN 13 THEN '星光桌游社'
      WHEN 14 THEN '青春电竞空间'
      ELSE `name`
    END,
    `area` = CASE `id`
      WHEN 1 THEN '一食堂' WHEN 2 THEN '北苑生活区' WHEN 3 THEN '教学区'
      WHEN 4 THEN '图书馆' WHEN 5 THEN '东门生活区' WHEN 6 THEN '南门生活区'
      WHEN 7 THEN '行政服务区' WHEN 8 THEN '西苑生活区' WHEN 9 THEN '图书馆'
      WHEN 10 THEN '体育馆' WHEN 11 THEN '校医院' WHEN 12 THEN '大学生活动中心'
      WHEN 13 THEN '东门生活区' WHEN 14 THEN '北苑生活区'
      ELSE `area`
    END,
    `address` = CASE `id`
      WHEN 1 THEN '一食堂一层东侧' WHEN 2 THEN '北苑生活区 1 号楼底商'
      WHEN 3 THEN '教学区共享餐厅二层' WHEN 4 THEN '图书馆一层阅读区旁'
      WHEN 5 THEN '校园东门服务街 12 号' WHEN 6 THEN '南门生活服务中心'
      WHEN 7 THEN '行政服务楼一层' WHEN 8 THEN '西苑宿舍区入口'
      WHEN 9 THEN '图书馆二层自习区' WHEN 10 THEN '综合体育馆一层'
      WHEN 11 THEN '校医院门诊楼一层' WHEN 12 THEN '大学生活动中心 203 室'
      WHEN 13 THEN '东门生活街二层' WHEN 14 THEN '北苑生活区活动室'
      ELSE `address`
    END,
    `avg_price` = CASE `id`
      WHEN 1 THEN 18 WHEN 2 THEN 22 WHEN 3 THEN 25 WHEN 4 THEN 20 WHEN 5 THEN 16
      WHEN 6 THEN 12 WHEN 7 THEN 8 WHEN 8 THEN 10 WHEN 9 THEN 15 WHEN 10 THEN 30
      WHEN 11 THEN 0 WHEN 12 THEN 0 WHEN 13 THEN 28 WHEN 14 THEN 35 ELSE `avg_price`
    END,
    `open_hours` = CASE `id`
      WHEN 1 THEN '06:30-20:30' WHEN 2 THEN '10:30-21:30' WHEN 3 THEN '10:00-20:00'
      WHEN 4 THEN '08:00-22:00' WHEN 5 THEN '09:00-22:00' WHEN 6 THEN '09:00-20:00'
      WHEN 7 THEN '08:30-18:00' WHEN 8 THEN '07:00-23:00' WHEN 9 THEN '08:00-22:30'
      WHEN 10 THEN '09:00-21:30' WHEN 11 THEN '08:00-17:30' WHEN 12 THEN '09:00-21:00'
      WHEN 13 THEN '13:00-22:30' WHEN 14 THEN '12:00-23:00' ELSE `open_hours`
    END;

UPDATE `tb_blog`
SET `shop_id` = CASE `id` WHEN 4 THEN 1 WHEN 5 THEN 4 WHEN 6 THEN 9 WHEN 7 THEN 13 ELSE `shop_id` END,
    `title` = CASE `id`
      WHEN 4 THEN '晚课后的能量补给｜一食堂风味窗口'
      WHEN 5 THEN '图书馆自习搭子必备｜咖啡角安利'
      WHEN 6 THEN '期末周自习攻略｜知行自习空间'
      WHEN 7 THEN '社团团建去哪儿｜星光桌游社'
      ELSE `title`
    END,
    `images` = CASE `id`
      WHEN 4 THEN '/imgs/campus/cafeteria-meal.png'
      WHEN 5 THEN '/imgs/campus/coffee-study.png'
      WHEN 6 THEN '/imgs/campus/study-room.png'
      WHEN 7 THEN '/imgs/campus/club-boardgame.png'
      ELSE `images`
    END,
    `content` = CASE `id`
      WHEN 4 THEN '晚课结束不想走太远，一食堂风味窗口出餐快、分量足。推荐烤肉饭和热汤，适合赶作业前快速补充能量。<br/>📍一食堂一层东侧'
      WHEN 5 THEN '图书馆一层的咖啡角很适合小组讨论。插座充足、座位安静，下午来一杯咖啡继续写课程作业。<br/>📍图书馆一层阅读区旁'
      WHEN 6 THEN '期末周来知行自习空间，座位预约方便，晚上也能安心复习。记得带好学生证，学习效率拉满。<br/>📍图书馆二层自习区'
      WHEN 7 THEN '周末社团团建选了星光桌游社，桌游种类多，还有新手教学。适合 4 到 8 人一起放松。<br/>📍东门生活街二层'
      ELSE `content`
    END
WHERE `id` IN (4,5,6,7);

UPDATE `tb_voucher`
SET `title` = '校园餐饮代金券',
    `sub_title` = '支付 40 元，抵扣 50 元',
    `rules` = '校园餐饮商户通用\n无需预约\n每位同学限购一张\n不兑现、不找零\n仅限到店核销',
    `pay_value` = 4000,
    `actual_value` = 5000
WHERE `id` = 1;

COMMIT;
