# D04 宿主机对照实验

状态：Windows x64 宿主机质量 PoC，**不是已接入 Android 的 librime InputEngine**。
生产仍使用 `PinyinInputEngine`；不向 APK 打包 DLL、方案或词库。

## 复现

需要 Windows x64 Python 3、Git、支持 7z 的 Windows tar，以及仓库原有 Java/Android 构建环境。
依赖准备需要联网；引擎查询完全离线。不要在真实 Rime 用户目录中运行。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File eval/d04/bootstrap.ps1

$env:BITAP_D04_EXPORT = '1'
try {
    .\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.D04BaselineExportTest --rerun-tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'baseline export failed' }
} finally { Remove-Item Env:BITAP_D04_EXPORT -ErrorAction SilentlyContinue }

# --work 必须是全新的实验目录；已有 user 子目录时报错，不自动删除或复用。
python eval/d04/rime_probe.py --dll .tmp/d04/bootstrap/native/dist/lib/rime.dll --shared .tmp/d04/bootstrap/shared --work .tmp/d04/my-run-01 --corpus eval/v2/corpus/pinyin_cases_v2_dev.tsv --out build/d04/rime-dev.jsonl
python eval/d04/compare.py --corpus eval/v2/corpus/pinyin_cases_v2_dev.tsv --baseline build/d04/bitap-dev.jsonl --rime build/d04/rime-dev.jsonl --out build/d04/comparison-dev.json
python -m unittest discover -s eval/d04 -p 'test_*.py' -v
```

默认只跑 dev。validation 不是日常调参集，Python 工具要求显式 `--acceptance`，Kotlin 导出工具只支持 dev。不能把自动生成的一份宿主数据冒充 Android 或发布验收。

## 数据与语义

- 自研分支经真实 `InputEngine` 适配器查询，无学习、无上下文。
- librime 1.17.0（33e7814）MSVC x64 官方发行包；固定 SHA-256 后才解压。
- 词典固定 `rime/rime-pinyin-simp@0c6861ef7420ee780270ca6d993d18d4101049d0`，不按评测修改。
- `bitap_d04.schema.yaml` 是基于该方案的受控最小配置：保留简拼/原有 derive，禁用学习和补全，不含反查、标点、识别器或第三方插件配置。不能称为“所有 Rime 默认能力”。
- 由于词典不同，这是系统组合比较，不是纯解码算法消融。
- 两边对完整输入进行查询；计时为返回前 8 项的宿主耗时，oracle 和完整覆盖池上限 64。自研对 8/64 分别查询，因为候选配额可能随 limit 改变。
- Rime C 候选迭代器不暴露每项消费范围。因此在禁止学习的隔离 composition 上恢复输入并逐项模拟选择，只有提交文本恰好等于候选文本才标记完整覆盖；其余标为 partial，不捏造消费范围。诊断选择耗时排除在 latency8 外；它不写编辑器。
- 统一评分要求候选文本匹配且完整消费；空格/标点归一化、交互项排除沿用 v2。没有把 partial 候选过滤后重新排名。
- `rankCost` 修正为前五命中才计 rank+1，第 6–8 名也记 9。历史成本数值不能直接对比，Top1/Top5 不变。
- Python 工具只输出 case id 与候选，不复制期望句到生产资源。详细文件位于忽略的 `build/d04/`，版本库只保存聚合快照。

## 许可与来源

- [librime BSD-3-Clause LICENSE](https://github.com/rime/librime/blob/33e7814/LICENSE)；预编译发行包依赖与插件的完整许可清单仍需在 Android 分发前审查，BSD 主库许可不能代替它们。
- [pinyin-simp Apache-2.0 LICENSE](https://github.com/rime/rime-pinyin-simp/blob/0c6861ef7420ee780270ca6d993d18d4101049d0/LICENSE)，本目录包含 `LICENSE.pinyin-simp`。原方案作者 Gong Chen；词典源自 Android Pinyin IME，见上游 [AUTHORS](https://github.com/rime/rime-pinyin-simp/blob/0c6861ef7420ee780270ca6d993d18d4101049d0/AUTHORS)。已在 YAML 标注改动。
- 没有复制或引用 `rime-prelude` 的 LGPL 配置，`default.yaml` 为本项目最小原始配置。
- Tatoeba/jieba/pypinyin 的 v2 评测署名继续见 `eval/v2/corpus/ATTRIBUTION.md`。
- 实验原始依赖只在 `.tmp/`，本目录不重新分发第三方二进制或词典。

本次结果与限制见 `docs/D04_HOST_COMPARISON.md` 和 `docs/adr/0002-input-engine-d04.md`。
