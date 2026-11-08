# D04 同词典实验工具

用于把 `app/src/main/assets/pinyin_dict.tsv`（65,999 条词条）转换为 Rime `pinyin_simp.dict.yaml`，
再复用 `eval/d04/bitap_d04.schema.yaml` / `default.yaml` 跑同词典宿主对照。生产代码与词库不变。

## 文件

- `build_same_dictionary.py`：读取 asset，使用 pypinyin `lazy_pinyin(word, style=Style.NORMAL)`
  恢复音节边界，生成 Rime 词典、共享数据目录和 `pinyin_conversion_report.json`。
  - 词条格式：`word<TAB>音节 空格分隔<TAB>权重`。
  - `lve`/`nve` 机械规范化为 Rime 的 `lue`/`nue`（68 条），不按评测答案改词。
  - pypinyin flat 读音与 asset key 的差异全部记录；本版本为 0/65,999。
- `make_snapshot.py`：读取 `build/d04` 的原始导出和 compare 输出，生成
  `eval/d04/results/dev-host-same-dict-2026-11-08.json`（含 `candidateStructure` 诊断）。

## 命令

```powershell
python eval/d04/same_dictionary/build_same_dictionary.py

python eval/d04/rime_probe.py `
  --dll .tmp/d04/bootstrap/native/dist/lib/rime.dll `
  --shared build/d04/same-dictionary/shared `
  --work build/d04/same-dictionary/work-01 `
  --corpus eval/v2/corpus/pinyin_cases_v2_dev.tsv `
  --out build/d04/rime-samedict-dev.jsonl

python eval/d04/compare.py `
  --corpus eval/v2/corpus/pinyin_cases_v2_dev.tsv `
  --baseline build/d04/bitap-dev.jsonl `
  --rime build/d04/rime-samedict-dev.jsonl `
  --out build/d04/comparison-samedict-dev.json

python eval/d04/same_dictionary/make_snapshot.py
```

`--work` 必须是新目录（rime_probe 拒绝复用已有 user 目录）。
`build/d04` 与 `build/d04/same-dictionary` 均为不提交的构建产物；版本库只保留
`pinyin_conversion_report.json` 与 `eval/d04/results/dev-host-same-dict-2026-11-08.json`。
