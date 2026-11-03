# 图片来源

`app/src/main/assets/wallpapers/` 里的六张超清风光照取自 **Bing 每日壁纸（Bing Wallpaper）**，
仅用于这个自用桌面。每张的原始页面与版权信息如下，转载或再分发前请自行确认授权。

| 文件 | 主题 / 地点 | Bing 版权信息 |
|---|---|---|
| nature-01.jpg | 布莱埃斯湖，多洛米蒂山脉，意大利（群峰间的宁静） | © leonid_tit / Getty Images |
| nature-02.jpg | 黑森林，巴登-符腾堡州，德国（林间迷雾） | © Britus / Getty Images |
| nature-03.jpg | 池杉林，乔治亚州，美国（大自然的金色明镜） | © Chris Moore / Getty Images |
| nature-04.jpg | 秋日瀑布，西托沃附近，保加利亚（大自然的秋日乐章） | © Nadya85 / Getty Images |
| nature-05.jpg | 玛琳湖，贾斯珀国家公园，艾伯塔省，加拿大（群峰与宁静相遇之地） | © Andrey Popov / Getty Images |
| nature-06.jpg | 内藏山国立公园，韩国（静水映斑斓） | © Twenty47studio / Getty Images |

尺寸均为 3840×2160（Bing 的 `_UHD` 版本），运行时按 1/2 降采样到 1920×1080 再叠加模糊，
1080p 与 4K 电视上都够用，同时把每张的内存占用控制在约 4 MB。

要换成自己的照片：直接把 jpg 放进 `app/src/main/assets/wallpapers/`，文件名任意，
桌面会按文件名排序轮播，不需要改代码。
