import 'dart:convert';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

void main() =>
    runApp(const MaterialApp(debugShowCheckedModeBanner: false, home: Board()));

const bridge = MethodChannel('takeaway_board');
typedef Review = ({
  String shop,
  String dish,
  String note,
  bool good,
  String date,
});

class Board extends StatefulWidget {
  const Board({super.key});
  @override
  State<Board> createState() => _BoardState();
}

class _BoardState extends State<Board> with WidgetsBindingObserver {
  final reviews = <Review>[];
  final search = TextEditingController();
  int tab = 0;
  bool ready = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    restore();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && ready) restore();
  }

  List<Review> decode(String raw) => (jsonDecode(raw) as List).map((item) {
    final row = item as Map<String, dynamic>;
    return (
      shop: row['shop'] as String,
      dish: row['dish'] as String,
      note: row['note'] as String,
      good: row['good'] as bool,
      date: row['date'] as String,
    );
  }).toList();

  String encode() => jsonEncode(
    reviews
        .map(
          (r) => {
            'shop': r.shop,
            'dish': r.dish,
            'note': r.note,
            'good': r.good,
            'date': r.date,
          },
        )
        .toList(),
  );

  Future<void> restore() async {
    try {
      final stored = decode(await bridge.invokeMethod<String>('load') ?? '[]');
      if (mounted && ready) {
        setState(
          () => reviews
            ..clear()
            ..addAll(stored),
        );
        return;
      }
      if (mounted) reviews.addAll(stored);
    } catch (_) {
      if (mounted) notice('读取评价失败');
    }
    if (mounted) setState(() => ready = true);
  }

  void notice(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> save(List<Review> next) async {
    final previous = List<Review>.of(reviews);
    setState(() {
      reviews
        ..clear()
        ..addAll(next);
      ready = false;
    });
    try {
      await bridge.invokeMethod<void>('save', encode());
    } catch (_) {
      if (!mounted) return;
      setState(
        () => reviews
          ..clear()
          ..addAll(previous),
      );
      notice('保存失败，请重试');
    } finally {
      if (mounted) setState(() => ready = true);
    }
  }

  Future<void> edit([Review? existing]) async {
    final result = await showModalBottomSheet<Review>(
      context: context,
      isScrollControlled: true,
      builder: (_) => ReviewForm(existing: existing),
    );
    if (result == null || !mounted) return;
    await save([result, ...reviews.where((r) => r != existing)]);
  }

  Future<void> share() async {
    try {
      await bridge.invokeMethod<void>('share', '外卖红黑榜\n${encode()}');
    } catch (_) {
      if (mounted) notice('无法打开分享面板');
    }
  }

  Future<void> importReviews() async {
    final raw = await showDialog<String>(
      context: context,
      builder: (context) {
        var value = '';
        return AlertDialog(
          title: const Text('导入好友评价'),
          content: TextField(
            maxLines: 5,
            onChanged: (v) => value = v,
            decoration: const InputDecoration(hintText: '粘贴好友分享的完整内容'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, value),
              child: const Text('导入'),
            ),
          ],
        );
      },
    );
    if (raw == null || !mounted) return;
    try {
      final items = decode(raw.trim().replaceFirst('外卖红黑榜\n', ''));
      await save(
        {...reviews, ...items}.toList()
          ..sort((a, b) => b.date.compareTo(a.date)),
      );
    } catch (_) {
      notice('无法识别这段评价，请检查是否复制完整');
    }
  }

  Future<void> overlay() async {
    try {
      final enabled = await bridge.invokeMethod<bool>('overlay');
      if (mounted && enabled == false) {
        notice('允许显示在其他应用上层后，再点一次悬浮窗');
      }
    } catch (_) {
      if (mounted) notice('此设备无法开启悬浮入口');
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final keyword = search.text.trim().toLowerCase();
    final visible = reviews
        .where(
          (r) =>
              (tab == 0 || r.good == (tab == 1)) &&
              '${r.shop} ${r.dish} ${r.note}'.toLowerCase().contains(keyword),
        )
        .toList();
    final greens = reviews.where((r) => r.good).length;
    return Theme(
      data: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff235747)),
      ),
      child: Scaffold(
        backgroundColor: const Color(0xfff8f5ed),
        appBar: AppBar(
          backgroundColor: const Color(0xfff8f5ed),
          title: const Text(
            '外卖红黑榜',
            style: TextStyle(fontWeight: FontWeight.w800),
          ),
          actions: [
            IconButton(
              tooltip: '分享评价',
              onPressed: ready && reviews.isNotEmpty ? share : null,
              icon: const Icon(Icons.ios_share),
            ),
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'import') {
                  importReviews();
                } else {
                  overlay();
                }
              },
              itemBuilder: (_) => [
                PopupMenuItem(
                  value: 'import',
                  enabled: ready,
                  child: const Text('导入好友评价'),
                ),
                const PopupMenuItem(value: 'overlay', child: Text('悬浮窗')),
              ],
            ),
          ],
        ),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 100),
            children: [
              Container(
                padding: const EdgeInsets.all(22),
                decoration: BoxDecoration(
                  color: const Color(0xff173d33),
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      '点单记录',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      '红榜 $greens  ·  黑榜 ${reviews.length - greens}',
                      style: const TextStyle(color: Color(0xffd3e8db)),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: search,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  hintText: '搜索店铺、菜品或评价',
                  prefixIcon: const Icon(Icons.search),
                  filled: true,
                  fillColor: Colors.white,
                  border: OutlineInputBorder(
                    borderSide: BorderSide.none,
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              SegmentedButton<int>(
                segments: const [
                  ButtonSegment(value: 0, label: Text('全部')),
                  ButtonSegment(value: 1, label: Text('红榜')),
                  ButtonSegment(value: 2, label: Text('黑榜')),
                ],
                selected: {tab},
                onSelectionChanged: (v) => setState(() => tab = v.first),
              ),
              const SizedBox(height: 16),
              if (!ready) const LinearProgressIndicator(),
              if (ready && visible.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 48),
                  child: Center(
                    child: Text(
                      reviews.isEmpty ? '还没有评价，记录一次点单吧' : '没有符合条件的评价',
                    ),
                  ),
                ),
              for (final r in visible)
                Card(
                  color: Colors.white,
                  margin: const EdgeInsets.only(bottom: 10),
                  child: ListTile(
                    onTap: ready ? () => edit(r) : null,
                    leading: CircleAvatar(
                      backgroundColor: r.good
                          ? const Color(0xffffe6df)
                          : const Color(0xffe9e8ee),
                      child: Text(r.good ? '红' : '黑'),
                    ),
                    title: Text(
                      '${r.shop} · ${r.dish}',
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    subtitle: Text(
                      [
                        if (r.note.isNotEmpty) r.note,
                        r.date.split('T').first,
                      ].join('\n'),
                    ),
                    trailing: IconButton(
                      tooltip: '删除评价',
                      icon: const Icon(Icons.delete_outline),
                      onPressed: ready
                          ? () async {
                              final confirmed = await showDialog<bool>(
                                context: context,
                                builder: (context) => AlertDialog(
                                  title: const Text('删除这条评价？'),
                                  actions: [
                                    TextButton(
                                      onPressed: () => Navigator.pop(context),
                                      child: const Text('取消'),
                                    ),
                                    FilledButton(
                                      onPressed: () =>
                                          Navigator.pop(context, true),
                                      child: const Text('删除'),
                                    ),
                                  ],
                                ),
                              );
                              if (confirmed == true && mounted) {
                                await save(
                                  reviews.where((item) => item != r).toList(),
                                );
                              }
                            }
                          : null,
                    ),
                  ),
                ),
            ],
          ),
        ),
        floatingActionButton: FloatingActionButton.extended(
          onPressed: ready ? () => edit() : null,
          icon: const Icon(Icons.add),
          label: const Text('记一单'),
        ),
      ),
    );
  }
}

class ReviewForm extends StatefulWidget {
  const ReviewForm({super.key, this.existing});
  final Review? existing;
  @override
  State<ReviewForm> createState() => _ReviewFormState();
}

class _ReviewFormState extends State<ReviewForm> {
  final form = GlobalKey<FormState>();
  late final shop = TextEditingController(text: widget.existing?.shop);
  late final dish = TextEditingController(text: widget.existing?.dish);
  late final note = TextEditingController(text: widget.existing?.note);
  late bool good = widget.existing?.good ?? true;

  @override
  void dispose() {
    shop.dispose();
    dish.dispose();
    note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
    child: SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: form,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                widget.existing == null ? '记录点单' : '编辑评价',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 16),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: true, label: Text('红榜 · 会回购')),
                  ButtonSegment(value: false, label: Text('黑榜 · 不回购')),
                ],
                selected: {good},
                onSelectionChanged: (v) => setState(() => good = v.first),
              ),
              TextFormField(
                controller: shop,
                decoration: const InputDecoration(labelText: '店铺'),
                validator: (v) => v!.trim().isEmpty ? '请输入店铺名称' : null,
              ),
              TextFormField(
                controller: dish,
                decoration: const InputDecoration(labelText: '菜品'),
                validator: (v) => v!.trim().isEmpty ? '请输入菜品名称' : null,
              ),
              TextField(
                controller: note,
                maxLines: 3,
                decoration: const InputDecoration(labelText: '评价（可选）'),
              ),
              const SizedBox(height: 20),
              FilledButton(
                onPressed: () {
                  if (!form.currentState!.validate()) return;
                  Navigator.pop(context, (
                    shop: shop.text.trim(),
                    dish: dish.text.trim(),
                    note: note.text.trim(),
                    good: good,
                    date:
                        widget.existing?.date ??
                        DateTime.now().toIso8601String(),
                  ));
                },
                child: const Text('保存评价'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
