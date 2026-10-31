# 地点服务（0.8.0）

中国大陆、香港和澳门接入高德 Web服务 API：关键词 POI 搜索、GPS 坐标转换、逆地理编码。其他地区使用 Google 原生地图、Places API (New) 地点搜索与附近地点、Geocoding API 地址解析。

默认根据当前位置或日记位置的 ISO 国家/地区代码选择：CN/HK/MO 为高德，其他代码（包括 TW）为 Google。未知地区不靠矩形经纬度范围猜测；可手动选择大陆、香港、澳门或其他地区，支持跨地区搜索。城市参数可覆盖当前位置的城市偏置，港澳使用固定城市和 citylimit=true。

## Key

在高德控制台申请 **Web服务** Key，非 Android SDK Key。设置中保存后使用 Android Keystore AES-GCM 加密到独立偏好文件，不写入源码、日记 JSON 或 WebDAV。现有云存储凭据的命名空间和密钥别名保持兼容。错误信息按高德 infocode 分类，不回显 Key 或完整请求 URL。

## 坐标

历史记录和照片 EXIF 坐标默认 WGS84。高德 POI 返回值标记 GCJ02；记录的 `coordinateSystem`、`countryCode` 和 `city` 随位置一起保存，避免把高德结果误当 GPS，缓存键和位置比较也区分坐标系。

解析 WGS84 图片或当前位置时，先通过高德官方坐标转换接口取得请求用的高德坐标，再调用逆地理编码；保存的仍然是原始 GPS 坐标，只补充名称、地址与地区。已有 GCJ02 地点不再转换。港澳同样使用官方接口决定转换行为，不自行套用坐标偏移公式。多设备使用时应升级到支持坐标系字段的版本。

## 请求与验证

关键词搜索使用 /v3/place/text，返回 extensions=all 区域数据，过滤到明确的目标地区。搜索取消会取消 HTTP 调用；进程共享请求节流。结果缓存成功 7 天、无结果 5 分钟，缓存区分地区、城市和 Key，切换服务不会混用旧结果。

普通测试使用 MockWebServer 和独立 Android 配置命名空间，不改用户日记。显式联调可验证用户配置的 Key 在大陆、香港、澳门都返回带名称和 GCJ02 标记的结果。一次性的配置助手只在传入 configure-amap-key=true 时读取特定临时文件，将用户主动提供的 Key 加密保存并删除临时文件，常规测试不触及实际凭据。

官方接口：
- https://developer.amap.com/api/webservice/guide/api/search/
- https://developer.amap.com/api/webservice/guide/api/georegeo
- https://developer.amap.com/api/webservice/guide/api/convert


## 0.7.0 地图选点与店铺刷新

选点改为全屏地图、搜索栏和附近商户列表。原生高德 SDK 固定使用 11.3.100_loc11.3.101_sea9.8.1，独立 Android平台 Key 与 Web服务 Key 共存并加密。SDK 在用户阅读政策并主动启用地图前不初始化；地图生命周期随页面和 Activity 释放，不自动开启 SDK 定位。地图显示位置均为官方转换后的 GCJ02，程序移动镜头不当成用户选点；用户拖动完成后再解析地址和附近商户。搜索结果可直接定位地图，确认后才写入日记。

“重新更新已有日记店铺信息”逐篇更新当前显示位置；优先按已知 POI ID 获取详情，否则在原坐标附近查询商户，75 米内才采用店名，无匹配保留既有名称并更新地址。原始坐标、图片顺序、日记文字和日期不改变。自定义名称，以及来源不明的旧手动位置名称，予以保留。候选仍为候选，正式日记标记待同步；可停止，已完成结果保留，更新期间变更了位置的日记不会被迟到结果覆盖。


### 0.7.1 距离排序

地图中心作为搜索距离参考，默认使用日记现有位置；无现有位置时使用当前位置。手机定位异步返回不会覆盖日记位置或用户选点。拖动地图更新参考点；同城关键词优先查询 50 公里周边（高德 sortrule=distance），无周边结果或指定其他城市时回退关键词搜索。统一使用官方转换的 GCJ02 坐标计算距离，返回结果和列表均按近到远排列，显示米/公里；搜索缓存包含参考位置，避免沿用其他地图中心的旧排序。地图中心仍在解析时搜索等待该解析完成，新的拖动会取消旧查询。

实际设备更新可通过显式参数 refresh-live-journals=true 调用同一个应用 ViewModel 更新动作，使用真实进度界面和既有写入保护；常规测试不会触发。执行前后分别备份数据库并核对正文、日期、照片列表及原始坐标，仅预期地点资料发生变化。

### 紧凑选点与各类附近地点

顶部城市输入改为点击展开，地区和城市共用一行；地点名称与距离同排，地址单行显示。移除重复的已选名称摘要，将修改店名入口放在列表标题旁。附近地点通过 `/v3/place/around` 显式查询高德全部 POI 大类（含住宅、学校、交通、地名地址、公共设施、通行设施等），避免省略 types 时接口默认只返回部分类型。1 公里范围内按距离查询，每页 25 条，滑动到底部自动加载下一页，失败时提供重试。附近列表过滤与固定“日记现有位置”相同的地点，优先按 POI ID 去重，旧位置按同名、同地址或同坐标系下 25 米内匹配，避免误删同名异地地点。位置变化时清空旧页并取消旧请求，后续页面也受位置版本与会话保护。批量补全店铺继续使用独立商户类型和原有 75 米规则。

分类参照：[高德 POI 分类编码](https://lbs.amap.com/api/webservice/download)。

## 0.8.0 Google 地图

中国大陆、香港、澳门使用高德；其他明确的 ISO 地区代码（包括 TW）使用 Google。地图拖动事件带服务来源，未知国家的临时 GPS 中心不会瞬间切回高德。Google 使用 WGS84，高德地图使用官方转换的 GCJ02；禁止向 Google 请求发送 GCJ02。跨地区搜索的 Google 结果使用 Google 地图展示，点选后切换地图、刷新附近地点，确认才写入日记。高德批量店铺补全的范围保持不变。

Google Maps SDK for Android 固定 20.1.0，需设备安装可用的 Google Play 服务。原生 MapView 随页面释放，地图手势与程序移动镜头区分，不启用 SDK 自定位。Google 搜索不复用高德缓存，不将 Google 响应写入长期搜索缓存；用户确认的位置保存坐标、名称、地址、来源、Place ID 和返回的第三方署名，选点列表展示署名及安全的 HTTPS 来源链接。

Google 附近搜索不指定类型、半径 1 公里、DISTANCE 排序，每次最多 20 条，API 不提供分页，界面提示通过移动地图或关键词搜索更多地点；不会伪造分页。高德仍按每页 25 条自动续页。两种地图保留日记原位置、同地点去重和自定义店名。

### 配置

Google Cloud 项目需关联结算，并启用 Maps SDK for Android、Places API (New)、Geocoding API。

- Android 地图 Key：限制到 `com.firzen.dailyone` 及安装包签名 SHA1，并只允许 Maps SDK for Android。写入已忽略的 `local.properties` 中 `google.maps.api.key=...`，或设置本地环境变量 `DAILYONE_GOOGLE_MAPS_KEY`，也可使用 Gradle 属性 `GOOGLE_MAPS_API_KEY`。构建注入标准 Android Manifest 元数据；源码和仓库不含实际 Key。地图 Key 变更后需要重建安装包。
- 地点服务 Key：API 限制为 Places API (New) 和 Geocoding API，在 App 设置中填写；通过现有独立 Keystore 加密存储，不写入日记或同步。当前为个人客户端直接调用 REST API，不使用服务器 IP 或网页来源限制。正式扩展服务端时可迁移到受控代理。
- Key 缺失或无权限分别显示 Google 配置错误；不回显服务器错误正文、密钥或请求 URL。

官方参考：[Android 地图配置](https://developers.google.com/maps/documentation/android-sdk/get-api-key)、[附近搜索](https://developers.google.com/maps/documentation/places/web-service/nearby-search)、[文本搜索](https://developers.google.com/maps/documentation/places/web-service/text-search)、[逆地理编码](https://developers.google.com/maps/documentation/geocoding/requests-reverse-geocoding)。

### Google 真机联调

Google 地图渲染验证只在显式传入 `live-google-map=true` 时执行，使用公开的东京站坐标和实际地图组件，等待所有瓦片完成加载的回调后截图。`live-google-place-lookup=true` 验证地点搜索、附近地点和地址解析。联调期间临时保持测试界面亮屏并在 Activity 关闭时释放，避免系统暂停后台网络；普通回归不会调用真实 Google 接口。临时地点 Key 配置文件在加密保存后删除，Android 地图 Key 仅保留在被 Git 忽略的本地构建配置。
