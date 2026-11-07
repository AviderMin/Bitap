# -*- coding: utf-8 -*-
"""Builds the independent pinyin quality corpus.

Sentences are hand-written natural Chinese (not generated from the engine's
dictionary). Pinyin is produced with the generic pypinyin library so the
corpus stays orthographically consistent; a few explicit-separator cases are
listed verbatim. Run from the repository root:

    python eval/build_corpus.py

The script writes eval/pinyin_cases_dev.tsv and eval/pinyin_cases_holdout.tsv
and prints only ASCII validation statistics.
"""
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pypinyin import lazy_pinyin, Style


def py(text: str) -> str:
    return "".join(lazy_pinyin(text, style=Style.NORMAL))


# category, chinese expected (may contain |), note, optional explicit input
Case = tuple

DEV = [
    # ---------------- daily chat ----------------
    ("daily_chat", "你吃饭了吗", "日常寒暄"),
    ("daily_chat", "我刚下班", "日常聊天"),
    ("daily_chat", "晚上一起吃饭吧", "日常约饭"),
    ("daily_chat", "明天有空吗", "日常询问"),
    ("daily_chat", "好的没问题", "日常应答"),
    ("daily_chat", "我马上到家", "日常报平安"),
    ("daily_chat", "今天好累啊", "日常吐槽"),
    ("daily_chat", "你先睡吧", "日常关心"),
    ("daily_chat", "周末去逛街吗", "日常邀约"),
    ("daily_chat", "这周末你有安排吗", "日常询问安排"),
    ("daily_chat", "我在路上了", "日常回复"),
    ("daily_chat", "别着急慢慢来", "日常安慰"),
    # ---------------- time / place ----------------
    ("time_place", "明天早上八点", "时间"),
    ("time_place", "下午三点开会", "时间"),
    ("time_place", "晚上七点半", "时间"),
    ("time_place", "后天中午", "时间"),
    ("time_place", "下周三下午", "时间"),
    ("time_place", "在北京西站", "地点"),
    ("time_place", "上海浦东机场", "地点"),
    ("time_place", "图书馆在哪儿", "地点询问"),
    ("time_place", "会议室在五楼", "地点"),
    ("time_place", "早上九点出发", "时间"),
    ("time_place", "明天上午十点半", "时间"),
    ("time_place", "地铁站怎么走", "问路"),
    # ---------------- work communication ----------------
    ("work_comm", "这个方案需要修改", "工作沟通"),
    ("work_comm", "请把文件发给我", "工作沟通"),
    ("work_comm", "项目进度怎么样", "工作沟通"),
    ("work_comm", "明天上午开评审会", "工作沟通"),
    ("work_comm", "这份报告写完没有", "工作沟通"),
    ("work_comm", "邮件我已经回复了", "工作沟通"),
    ("work_comm", "需求文档更新一下", "工作沟通"),
    ("work_comm", "客户下午过来", "工作沟通"),
    ("work_comm", "会议改到周四", "工作沟通"),
    ("work_comm", "测试环境部署好了", "工作沟通"),
    ("work_comm", "这个功能什么时候上线", "工作沟通"),
    ("work_comm", "预算还需要确认", "工作沟通"),
    # ---------------- unseen sentences / multiple wordings ----------------
    ("unseen_sentence", "今天中午吃什么", "未收录整句"),
    ("unseen_sentence", "我觉得这样不太好", "未收录整句"),
    ("unseen_sentence", "你能帮我个忙吗", "未收录整句"),
    ("unseen_sentence", "这个问题有点复杂", "未收录整句"),
    ("unseen_sentence", "他们已经在路上了", "未收录整句"),
    ("unseen_sentence", "我们一起想办法", "未收录整句"),
    ("unseen_sentence", "这件事我来处理", "未收录整句"),
    ("unseen_sentence", "你先看看再说", "未收录整句"),
    ("unseen_sentence", "我可能来不及了", "未收录整句"),
    ("unseen_sentence", "外面好像要下雨了", "未收录整句"),
    ("unseen_sentence", "他说得很有道理|他说的很有道理", "多种合理表达：的/得同音"),
    ("unseen_sentence", "我们换个地方聊吧", "未收录整句"),
    # ---------------- homophones ----------------
    ("homophone", "时间|事件|实践|世间", "同音词歧义：均可接受", "shijian"),
    ("homophone", "意识|仪式|一时|议事", "同音词歧义：均可接受", "yishi"),
    ("homophone", "公式|公示|攻势|工事", "同音词歧义：均可接受", "gongshi"),
    ("homophone", "其实|骑士|启示|歧视", "同音词歧义：均可接受", "qishi"),
    ("homophone", "及时|即使|技师|记事", "同音词歧义：均可接受", "jishi"),
    ("homophone", "重视|忠实|中式", "同音词歧义：均可接受", "zhongshi"),
    ("homophone", "事实|实施|试试|事事", "同音词歧义：均可接受", "shishi"),
    ("homophone", "项目|橡木", "同音词歧义：均可接受", "xiangmu"),
    ("homophone", "工艺|公益|公意|工蚁", "同音词歧义：均可接受", "gongyi"),
    ("homophone", "报名|保命", "同音词歧义：均可接受", "baoming"),
    ("homophone", "上午|商务", "同音词歧义：均可接受", "shangwu"),
    ("homophone", "地方|提防", "同音词歧义：均可接受", "difang"),
    # ---------------- long sentences / segmented selection ----------------
    ("long_segment", "我今天下午要去公司参加一个重要的会议", "长句：期望整句"),
    ("long_segment", "明天上午十点我们在会议室讨论项目进度", "长句：期望整句"),
    ("long_segment", "这个问题我们已经讨论过很多次了", "长句：期望整句"),
    ("long_segment", "你能不能帮我看看这份报告哪里有问题", "长句：期望整句"),
    ("long_segment", "如果明天天气好的话我们就去公园散步", "长句：期望整句"),
    ("long_segment", "我昨天在图书馆借了两本书", "长句：期望整句"),
    ("long_segment", "这个周末我想带家人去郊区玩一天", "长句：期望整句"),
    ("long_segment", "请你把会议纪要和下周计划一起发给我", "长句：期望整句"),
    ("long_segment", "我们先确认需求范围再安排开发排期", "长句：期望整句"),
    ("long_segment", "他说下个月要去上海出差一个星期", "长句：期望整句"),
    ("long_segment", "今天下班以后", "分段选词：期望首段可先选中", "jintianxiabanyihouwomenyiqiquchaoshimaidiandongxi"),
    ("long_segment", "明天上午", "分段选词：期望首段可先选中", "mingtianshangwuwuihuibaxiugaihoudefanganfadaonideyouxiang"),
    # ---------------- mixed Chinese / English ----------------
    ("mixed_en", "我要买iPhone", "中英混输：iPhone 原文提交", "woyaomaiiPhone"),
    ("mixed_en", "明天发你PDF", "中英混输：PDF 原文提交", "mingtianfaniPDF"),
    ("mixed_en", "我需要下载一个APP", "中英混输：APP 原文提交", "woxuyaoxiazaiyigeAPP"),
    ("mixed_en", "这个版本已经release", "中英混输：release 原文提交", "zhegebanbenyijingrelease"),
    ("mixed_en", "我们用Python", "中英混输：Python 原文提交", "womenyongPython"),
    ("mixed_en", "这个", "中英混输：App 原文提交，评测“这个”前缀", "zhegeApphenhao"),
    ("mixed_en", "你在", "中英混输：Slack 原文提交，评测“你在”前缀", "nizaiSlackshanghuifuyixia"),
    ("mixed_en", "记得", "中英混输：check 原文提交，评测“记得”前缀", "jidecheckyoujian"),
    ("mixed_en", "会议改到", "中英混输：Teams 原文提交，评测“会议改到”前缀", "huiyigaidaoTeamsjinxing"),
    ("mixed_en", "这个功能在", "中英混输：iOS 原文提交，评测“这个功能在”前缀", "zhegegongnengzaiiOSshangyoudianwenti"),
    ("mixed_en", "我明天要开会review", "中英混输：review 原文提交", "womingtianyaokaihuireview"),
    ("mixed_en", "我们先看看feedback", "中英混输：feedback 原文提交", "womenxiankankanfeedback"),
    # ---------------- numbers and punctuation ----------------
    ("number_punct", "三十分钟后", "数字：中文数词"),
    ("number_punct", "二百五十块钱", "数字：中文数词"),
    ("number_punct", "百分之九十", "数字：百分比"),
    ("number_punct", "十月一号", "数字：日期"),
    ("number_punct", "第一次", "数字：序数"),
    ("number_punct", "四点零五分", "数字：时间"),
    ("number_punct", "明天", "阿拉伯数字：10 原文提交，评测首个中文段", "mingtian10dianjian"),
    ("number_punct", "这个手机花了两千多块", "数字：金额"),
    ("number_punct", "我在车站等了二十分钟", "数字：时长"),
    ("number_punct", "西安", "标点：撇号显式音节分隔", "xi'an"),
    ("number_punct", "天安门", "标点：撇号显式音节分隔", "tian'anmen"),
    ("number_punct", "你好", "标点：输入末尾标点应被忽略", "nihao,"),
    # ---------------- shorthand (all initials) ----------------
    ("shorthand", "你好", "简拼：全声母", "nh"),
    ("shorthand", "我们", "简拼：全声母", "wm"),
    ("shorthand", "你们", "简拼：全声母", "nm"),
    ("shorthand", "他们", "简拼：全声母", "tm"),
    ("shorthand", "再见", "简拼：全声母", "zj"),
    ("shorthand", "谢谢", "简拼：全声母", "xx"),
    ("shorthand", "什么", "简拼：全声母", "sm"),
    ("shorthand", "怎么", "简拼：全声母", "zm"),
    ("shorthand", "为什么", "简拼：全声母", "wsm"),
    ("shorthand", "哈哈", "简拼：全声母", "hh"),
    ("shorthand", "拜拜", "简拼：全声母", "bb"),
    ("shorthand", "开心", "简拼：全声母", "kx"),
    # ---------------- mixed pinyin ----------------
    ("mixed_pinyin", "中国", "混拼：zhong+g", "zhongg"),
    ("mixed_pinyin", "北京", "混拼：bei+j", "beij"),
    ("mixed_pinyin", "上海", "混拼：shang+h", "shangh"),
    ("mixed_pinyin", "明天", "混拼：ming+t", "mingt"),
    ("mixed_pinyin", "今天", "混拼：jin+t", "jint"),
    ("mixed_pinyin", "朋友", "混拼：peng+y", "pengy"),
    ("mixed_pinyin", "电话", "混拼：dian+h", "dianh"),
    ("mixed_pinyin", "工作", "混拼：gong+z", "gongz"),
    ("mixed_pinyin", "学习", "混拼：xue+x", "xuex"),
    ("mixed_pinyin", "晚上", "混拼：wan+s", "wans"),
    ("mixed_pinyin", "开心", "混拼：kai+x", "kaix"),
    ("mixed_pinyin", "吃饭", "混拼：chi+f", "chif"),
    # ---------------- common typos ----------------
    ("typo", "你好", "漏字母：正确拼音 nihao", "nhao"),
    ("typo", "明天", "漏字母：正确拼音 mingtian", "mintian"),
    ("typo", "大家", "漏字母：正确拼音 dajia", "daja"),
    ("typo", "我要", "多字母：正确拼音 woyao", "woyaoo"),
    ("typo", "今天", "多字母：正确拼音 jintian", "jintiann"),
    ("typo", "再见", "多字母：正确拼音 zaijian", "zaijiaan"),
    ("typo", "你好", "相邻交换：正确拼音 nihao", "nihoa"),
    ("typo", "朋友", "相邻交换：正确拼音 pengyou", "pengyuo"),
    ("typo", "时间", "相邻交换：正确拼音 shijian", "shijain"),
    ("typo", "你好", "邻键：正确拼音 nihao，o 误触 p", "nihap"),
    ("typo", "明天", "邻键：正确拼音 mingtian，n 误触 b", "mingtiab"),
    ("typo", "工作", "邻键：正确拼音 gongzuo，o 误触 i", "gongzui"),
]

HOLD = [
    # ---------------- daily chat ----------------
    ("daily_chat", "你最近怎么样", "日常问候"),
    ("daily_chat", "我明天休息", "日常聊天"),
    ("daily_chat", "一起去喝咖啡吧", "日常邀约"),
    ("daily_chat", "家里一切都好", "日常报平安"),
    ("daily_chat", "我晚点回复你", "日常回复"),
    ("daily_chat", "明天见吧", "日常告别"),
    ("daily_chat", "你到家了吗", "日常关心"),
    ("daily_chat", "路上小心", "日常叮嘱"),
    ("daily_chat", "好久不见", "日常寒暄"),
    ("daily_chat", "最近工作忙吗", "日常问候"),
    ("daily_chat", "我先洗澡了", "日常聊天"),
    ("daily_chat", "明天再说吧", "日常回复"),
    # ---------------- time / place ----------------
    ("time_place", "今天下午四点", "时间"),
    ("time_place", "晚上八点见", "时间"),
    ("time_place", "星期五上午", "时间"),
    ("time_place", "在火车站等你", "地点"),
    ("time_place", "北京南站", "地点"),
    ("time_place", "公司楼下咖啡店", "地点"),
    ("time_place", "明天早上七点起床", "时间"),
    ("time_place", "下午两点半", "时间"),
    ("time_place", "公园门口见", "地点"),
    ("time_place", "这附近有超市吗", "地点询问"),
    ("time_place", "机场大巴在哪里坐", "问路"),
    ("time_place", "我家在朝阳区", "地点"),
    # ---------------- work communication ----------------
    ("work_comm", "合同明天签", "工作沟通"),
    ("work_comm", "请查收附件", "工作沟通"),
    ("work_comm", "周报周五提交", "工作沟通"),
    ("work_comm", "这个版本先不发布", "工作沟通"),
    ("work_comm", "需求还要再改", "工作沟通"),
    ("work_comm", "会议室已预订", "工作沟通"),
    ("work_comm", "数据明天汇总", "工作沟通"),
    ("work_comm", "请确认一下时间", "工作沟通"),
    ("work_comm", "设计稿已经发过去了", "工作沟通"),
    ("work_comm", "负责人是谁", "工作沟通"),
    ("work_comm", "下个季度目标定了吗", "工作沟通"),
    ("work_comm", "先把问题记录下来", "工作沟通"),
    # ---------------- unseen sentences ----------------
    ("unseen_sentence", "明天要不要一起吃饭", "未收录整句"),
    ("unseen_sentence", "这个周末你有什么打算", "未收录整句"),
    ("unseen_sentence", "我最近在学做饭", "未收录整句"),
    ("unseen_sentence", "你帮我看看这个", "未收录整句"),
    ("unseen_sentence", "他可能已经知道了", "未收录整句"),
    ("unseen_sentence", "外面天气突然变冷了", "未收录整句"),
    ("unseen_sentence", "我们下午再讨论吧", "未收录整句"),
    ("unseen_sentence", "这个价格可以接受", "未收录整句"),
    ("unseen_sentence", "你先回去休息吧", "未收录整句"),
    ("unseen_sentence", "我明天上午联系你", "未收录整句"),
    ("unseen_sentence", "这件事情很重要", "未收录整句"),
    ("unseen_sentence", "大家觉得怎么样", "未收录整句"),
    # ---------------- homophones ----------------
    ("homophone", "医疗|意料", "同音词歧义：均可接受", "yiliao"),
    ("homophone", "厨师|出师|初试|处世", "同音词歧义：均可接受", "chushi"),
    ("homophone", "公园|公元", "同音词歧义：均可接受", "gongyuan"),
    ("homophone", "设计|射击|涉及", "同音词歧义：均可接受", "sheji"),
    ("homophone", "天气|田七", "同音词歧义：均可接受", "tianqi"),
    ("homophone", "方案|反感", "同音词歧义：均可接受", "fangan"),
    ("homophone", "管理|惯例", "同音词歧义：均可接受", "guanli"),
    ("homophone", "经济|经纪|竞技", "同音词歧义：均可接受", "jingji"),
    ("homophone", "知识|指示|只是", "同音词歧义：均可接受", "zhishi"),
    ("homophone", "想象|想像|想想", "同音词歧义：均可接受", "xiangxiang"),
    ("homophone", "实行|施行|时兴", "同音词歧义：均可接受", "shixing"),
    ("homophone", "检查|检察|监察", "同音词歧义：均可接受", "jiancha"),
    # ---------------- long sentences / segmented selection ----------------
    ("long_segment", "我们公司明年计划在南方开一个新的分公司", "长句：期望整句"),
    ("long_segment", "昨天晚上的电影特别好看但是结束得太晚了", "长句：期望整句"),
    ("long_segment", "你到了以后给我打个电话我去楼下接你", "长句：期望整句"),
    ("long_segment", "这份合同需要法务和财务两个部门都确认", "长句：期望整句"),
    ("long_segment", "如果周末不出差的话我打算在家好好休息", "长句：期望整句"),
    ("long_segment", "他每天早上六点半起床然后去公园跑步", "长句：期望整句"),
    ("long_segment", "请你在周五之前把测试结果整理成文档", "长句：期望整句"),
    ("long_segment", "我们这个季度的目标是完成三个主要功能", "长句：期望整句"),
    ("long_segment", "从上个月开始我就在准备这个项目的材料", "长句：期望整句"),
    ("long_segment", "吃饭以后", "分段选词：期望首段可先选中", "chifanyihouwomenyanzhehebianzoulehenjiu"),
    ("long_segment", "下周三之前", "分段选词：期望首段可先选中", "xiazhousanzhiqianqingbayusuanbiaofageicaiwubumen"),
    ("long_segment", "明天下午两点", "分段选词：期望首段可先选中", "mingtianxiawuliangdianwomenzaidatingmenkoujihechufa"),
    # ---------------- mixed Chinese / English ----------------
    ("mixed_en", "我要买个iPad", "中英混输：iPad 原文提交", "woyaomaigeiiPad"),
    ("mixed_en", "明天发你Excel", "中英混输：Excel 原文提交", "mingtianfaniExcel"),
    ("mixed_en", "我们需要做一个Demo", "中英混输：Demo 原文提交", "womenxuyaozuoyigeDemo"),
    ("mixed_en", "这个文档已经update", "中英混输：update 原文提交", "zhegewendangyijingupdate"),
    ("mixed_en", "我先看看email", "中英混输：email 原文提交", "woxiankankanemail"),
    ("mixed_en", "在公司", "中英混输：Slack 原文提交，评测“在公司”前缀", "zaigongsiSlackshangzhaowo"),
    ("mixed_en", "今天", "中英混输：check 原文提交，评测“今天”前缀", "jintianchecklema"),
    ("mixed_en", "报告在", "中英混输：meeting 原文提交，评测“报告在”前缀", "baogaozaimeetingqianfageiwo"),
    ("mixed_en", "我的", "中英混输：account 原文提交，评测“我的”前缀", "wodeaccountbeifengle"),
    ("mixed_en", "请把简历导到Trello", "中英混输：Trello 原文提交", "qingbajianlidaodaotrello"),
    ("mixed_en", "明天整理Report", "中英混输：Report 原文提交", "mingtianzhengliReport"),
    ("mixed_en", "这个", "中英混输：API 原文提交，评测“这个”前缀", "zhegeAPIxuyaochongxinceshi"),
    # ---------------- numbers and punctuation ----------------
    ("number_punct", "五十分钟以后", "数字：中文数词"),
    ("number_punct", "三百五十块", "数字：金额"),
    ("number_punct", "百分之三", "数字：百分比"),
    ("number_punct", "十二月二十五号", "数字：日期"),
    ("number_punct", "第二次", "数字：序数"),
    ("number_punct", "九点零八分", "数字：时间"),
    ("number_punct", "我要买", "阿拉伯数字：3 原文提交，评测首个中文段", "woyaomai3ge"),
    ("number_punct", "这件衣服花了几百块", "数字：金额"),
    ("number_punct", "他在家等了一个小时", "数字：时长"),
    ("number_punct", "长安", "标点：撇号显式音节分隔", "chang'an"),
    ("number_punct", "北京", "标点：空格分隔音节", "bei jing"),
    ("number_punct", "再见", "标点：输入末尾标点应被忽略", "zaijian!"),
    # ---------------- shorthand ----------------
    ("shorthand", "大家", "简拼：全声母", "dj"),
    ("shorthand", "朋友", "简拼：全声母", "py"),
    ("shorthand", "手机|时间|世界", "简拼：全声母，存在歧义", "sj"),
    ("shorthand", "工作", "简拼：全声母", "gz"),
    ("shorthand", "银行", "简拼：全声母", "yh"),
    ("shorthand", "电话", "简拼：全声母", "dh"),
    ("shorthand", "明天", "简拼：全声母", "mt"),
    ("shorthand", "今天", "简拼：全声母", "jt"),
    ("shorthand", "早上", "简拼：全声母", "zs"),
    ("shorthand", "晚上", "简拼：全声母", "ws"),
    ("shorthand", "喜欢", "简拼：全声母", "xh"),
    ("shorthand", "对不起", "简拼：全声母", "dbq"),
    # ---------------- mixed pinyin ----------------
    ("mixed_pinyin", "广州", "混拼：guang+z", "guangz"),
    ("mixed_pinyin", "深圳", "混拼：shen+z", "shenz"),
    ("mixed_pinyin", "杭州", "混拼：hang+z", "hangz"),
    ("mixed_pinyin", "下雨", "混拼：xia+y", "xiay"),
    ("mixed_pinyin", "天气", "混拼：tian+q", "tianq"),
    ("mixed_pinyin", "公园", "混拼：gong+y", "gongy"),
    ("mixed_pinyin", "上班", "混拼：shang+b", "shangb"),
    ("mixed_pinyin", "下班", "混拼：xia+b", "xiab"),
    ("mixed_pinyin", "睡觉", "混拼：shui+j", "shuij"),
    ("mixed_pinyin", "出发", "混拼：chu+f", "chuf"),
    ("mixed_pinyin", "到底", "混拼：dao+d", "daod"),
    ("mixed_pinyin", "结果", "混拼：jie+g", "jieg"),
    # ---------------- common typos ----------------
    ("typo", "我们", "漏字母：正确拼音 women", "wmen"),
    ("typo", "学习", "漏字母：正确拼音 xuexi", "xuei"),
    ("typo", "手机", "漏字母：正确拼音 shouji", "shoji"),
    ("typo", "咖啡", "多字母：正确拼音 kafei", "kafeii"),
    ("typo", "想法", "多字母：正确拼音 xiangfa", "xiangfaa"),
    ("typo", "昨天", "多字母：正确拼音 zuotian", "zuotiann"),
    ("typo", "早上", "相邻交换：正确拼音 zaoshang", "zaosahng"),
    ("typo", "晚上", "相邻交换：正确拼音 wanshang", "wanshagn"),
    ("typo", "电话", "相邻交换：正确拼音 dianhua", "dianuha"),
    ("typo", "谢谢", "邻键：正确拼音 xiexie，e 误触 r", "xiexir"),
    ("typo", "明天", "邻键：正确拼音 mingtian，n 误触 m", "mingtiam"),
    ("typo", "朋友", "邻键：正确拼音 pengyou，u 误触 i", "pengyoi"),
]


def normalize_input(raw: str) -> str:
    # keep letters, digits, spaces, apostrophe and common punctuation; lowercase
    return raw.lower()


def build(cases, prefix):
    rows = []
    for index, case in enumerate(cases, start=1):
        category, expected, note = case[0], case[1], case[2]
        explicit = case[3] if len(case) > 3 else None
        if explicit is None:
            first = expected.split("|")[0]
            raw = py(first)
        else:
            raw = explicit
        rows.append({
            "id": f"{prefix}-{category}-{index:02d}",
            "category": category,
            "input": normalize_input(raw),
            "expected": expected,
            "note": note,
        })
    return rows


def validate(rows, label, errors):
    seen_inputs = {}
    for row in rows:
        for field in ("id", "category", "input", "expected", "note"):
            if not row[field]:
                errors.append(f"{label} {row['id']}: empty {field}")
            if "\t" in row[field] or "\n" in row[field]:
                errors.append(f"{label} {row['id']}: tab/newline in {field}")
        key = row["input"]
        if key in seen_inputs:
            errors.append(f"{label} duplicate input {key!r}: {seen_inputs[key]} vs {row['id']}")
        seen_inputs[key] = row["id"]
        if row["category"] in ("typo", "shorthand", "mixed_pinyin"):
            continue
        for alt in row["expected"].split("|"):
            cjk = "".join(ch for ch in alt if "\u4e00" <= ch <= "\u9fff")
            if not cjk:
                continue
            expected_py = py(cjk)
            normalized_input = (
                row["input"]
                .replace("'", "")
                .replace(" ", "")
                .replace("-", "")
                .rstrip(",.!?")
            )
            if expected_py == normalized_input:
                continue
            if row["category"] in ("mixed_en", "long_segment", "number_punct") and normalized_input.startswith(expected_py):
                continue
            errors.append(
                f"{label} {row['id']}: pinyin mismatch input={row['input']!r} alt={alt!r} pypinyin={expected_py!r}"
            )


def main():
    dev = build(DEV, "dev")
    hold = build(HOLD, "hold")
    errors = []
    validate(dev, "dev", errors)
    validate(hold, "hold", errors)

    dev_inputs = {r["input"] for r in dev}
    for row in hold:
        if row["input"] in dev_inputs:
            errors.append(f"cross-set duplicate input {row['input']!r} ({row['id']})")

    root = Path(__file__).resolve().parent.parent
    out_dev = root / "eval" / "pinyin_cases_dev.tsv"
    out_hold = root / "eval" / "pinyin_cases_holdout.tsv"
    for path, rows in ((out_dev, dev), (out_hold, hold)):
        with path.open("w", encoding="utf-8", newline="\n") as handle:
            for row in rows:
                handle.write("\t".join([row["id"], row["category"], row["input"], row["expected"], row["note"]]) + "\n")

    print(f"dev={len(dev)} holdout={len(hold)} total={len(dev)+len(hold)}")
    from collections import Counter
    print("dev categories=" + repr(dict(Counter(r['category'] for r in dev))))
    print("hold categories=" + repr(dict(Counter(r['category'] for r in hold))))
    print("errors=" + str(len(errors)))
    for error in errors:
        print("ERROR " + error)


if __name__ == "__main__":
    main()
