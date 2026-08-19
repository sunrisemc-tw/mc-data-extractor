import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.SharedConstants;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dumps the vanilla creative-mode tab layout and the full item registry for the
 * Minecraft version found inside the runtime server jar on the classpath.
 *
 * Boots the game exactly like a real server:
 *   bootstrap -> vanilla datapack -> tag loading -> worldgen registries ->
 *   data components -> CreativeModeTabs.tryRebuildTabContents
 *
 * Usage: java -cp "<runtime-server.jar>:<libraries>/*" DumpCreativeTabs [outputDir] [lang.json] [en_us.json] [assets-index.json]
 * Writes items.json + creative-tabs.json (pretty-printed) into outputDir (default ".").
 * Optional lang files add name_<lang> / display_name_<lang> translation fields.
 * Optional Mojang assets-index.json adds official CDN icon_url fields.
 */
public class DumpCreativeTabs {
    static PrintWriter dbg;
    static final String RES_CDN = "https://resources.download.minecraft.net/";

    static Map<String, String> loadAssetIndex(String path) {
        Map<String, String> m = new HashMap<>();
        if (!nonEmpty(path)) return m;
        try {
            JsonObject idx = JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
            JsonObject objs = idx.getAsJsonObject("objects");
            for (Map.Entry<String, com.google.gson.JsonElement> e : objs.entrySet()) {
                m.put(e.getKey(), e.getValue().getAsJsonObject().get("hash").getAsString());
            }
            dbg.println("asset index loaded: " + path + " (" + m.size() + " objects)");
        } catch (Throwable t) {
            dbg.println("asset index load fail " + path + ": " + t);
        }
        return m;
    }

    static String iconUrl(Map<String, String> assets, String texturePath) {
        if (texturePath == null) return null;
        String h = assets.get(texturePath);
        return h != null ? RES_CDN + h.substring(0, 2) + "/" + h : null;
    }

    /** 找本地已抽出的紋理 (out/img/{item|block}/<name>.png), 回傳相對資料夾名 (item/block) 或 null。 */
    static String findLocalIcon(Path outDir, String name, boolean blockFirst) {
        String[] folders = blockFirst ? new String[]{"block", "item"} : new String[]{"item", "block"};
        for (String f : folders) {
            if (Files.exists(outDir.resolve("img").resolve(f).resolve(name + ".png"))) {
                return f;
            }
        }
        return null;
    }

    /** 照官方 item/block model 解析物品實際使用的紋理路徑 (如 "item/diamond_sword" / "block/melon_side")。 */
    static String modelTexture(Path outDir, String itemName) {
        String r = walkModel(outDir, "item/" + itemName);
        if (r != null) return r;
        return walkModel(outDir, "block/" + itemName); // 方塊物品常沒有專屬 item model
    }

    static String walkModel(Path outDir, String start) {
        java.util.LinkedHashMap<String, String> textures = new java.util.LinkedHashMap<>();
        String cur = start;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (true) {
            Path p = outDir.resolve("models").resolve(cur + ".json");
            if (!Files.exists(p)) break;
            String parent = null;
            try {
                JsonObject m = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if (m.has("textures")) {
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e
                            : m.getAsJsonObject("textures").entrySet()) {
                        if (!textures.containsKey(e.getKey())) {
                            textures.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                }
                if (m.has("parent")) {
                    String rawParent = m.get("parent").getAsString(); // may be "minecraft:block/x"
                    int ci = rawParent.indexOf(':');
                    parent = ci >= 0 ? rawParent.substring(ci + 1) : rawParent;
                }
            } catch (Throwable t) {
                break;
            }
            if (parent == null || (!parent.startsWith("item/") && !parent.startsWith("block/"))) break;
            if (!seen.add(parent)) break;
            cur = parent;
        }
        String picked = null;
        for (String key : new String[]{"layer0", "particle"}) {
            String v = textures.get(key);
            if (v != null && !v.isEmpty() && !v.startsWith("#")) { picked = v; break; }
        }
        if (picked == null) {
            for (String v : textures.values()) {
                if (v != null && !v.isEmpty() && !v.startsWith("#")) { picked = v; break; }
            }
        }
        if (picked == null) return null;
        int ci = picked.indexOf(':');
        String norm = ci >= 0 ? picked.substring(ci + 1) : picked; // 剝 namespace
        if (norm.indexOf('/') < 0) norm = "block/" + norm;          // 裸名 -> block/
        return norm;
    }

    static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    static Map<String, String> loadLang(String path) {
        Map<String, String> m = new HashMap<>();
        if (!nonEmpty(path)) return m;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                m.put(e.getKey(), e.getValue().getAsString());
            }
            dbg.println("lang loaded: " + path + " (" + m.size() + " keys)");
        } catch (Throwable t) {
            dbg.println("lang load fail " + path + ": " + t);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        dbg = new PrintWriter(Files.newBufferedWriter(outDir.resolve("dump-debug.txt")));
        String langFile = args.length > 1 ? args[1] : null;
        String enFile = args.length > 2 ? args[2] : null;
        String indexFile = args.length > 3 ? args[3] : null;
        String clientJarUrl = args.length > 4 ? args[4] : null;
        String langTag = nonEmpty(langFile)
                ? Path.of(langFile).getFileName().toString().replace(".json", "") : "";
        Map<String, String> lang = loadLang(langFile);
        Map<String, String> en = loadLang(enFile);
        Map<String, String> assets = loadAssetIndex(indexFile);
        java.util.function.Function<String, String> tr = k -> {
            String v = lang.get(k);
            return v != null ? v : en.get(k);
        };
        try {
            dbg.println("step0: start");
            SharedConstants.tryDetectVersion();
            dbg.println("step1: version ok");
            Bootstrap.bootStrap();
            dbg.println("step2: bootstrap ok");

            // vanilla datapack from the jar itself
            PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
            repo.reload();
            repo.setSelected(repo.getAvailableIds());
            List<net.minecraft.server.packs.PackResources> opened = repo.openAllSelected();
            ResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA, opened);
            dbg.println("step3: resource manager ok, namespaces=" + rm.getNamespaces());

            // bootstrap access over built-in registries
            RegistryAccess.Frozen bootstrapAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

            // bind tags for ALL built-in registries FIRST (dimension_type/enchantment reference these tags)
            List<net.minecraft.core.Registry.PendingTags<?>> preTags = TagLoader.loadTagsForExistingRegistries(rm, bootstrapAccess);
            preTags.forEach(p -> p.apply());
            dbg.println("step4: pre tags bound (" + preTags.size() + " registries)");

            // bootstrap list: every built-in registry IS a RegistryLookup
            List<HolderLookup.RegistryLookup<?>> bootstrap = new java.util.ArrayList<>();
            for (Object r : (Iterable) BuiltInRegistries.REGISTRY) {
                bootstrap.add((HolderLookup.RegistryLookup) r);
            }

            // load worldgen/dynamic registries the way the server does
            RegistryAccess.Frozen access = RegistryDataLoader.load(
                    rm, bootstrap, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run
            ).join();
            dbg.println("step5: worldgen registries loaded, registries=" + access.listRegistries().count());

            // compose FULL registry access: built-ins + loaded worldgen (like the real server)
            java.util.Map allRegs = new java.util.HashMap();
            for (Object r : (Iterable) BuiltInRegistries.REGISTRY) {
                Registry rr = (Registry) r;
                allRegs.put(rr.key(), rr);
            }
            for (Object l : access.listRegistries().toList()) {
                HolderLookup.RegistryLookup rl = (HolderLookup.RegistryLookup) l;
                allRegs.put(rl.key(), (Registry) rl);
            }
            RegistryAccess.Frozen fullAccess = new RegistryAccess.ImmutableRegistryAccess(allRegs).freeze();
            dbg.println("step6: full access registries=" + fullAccess.listRegistries().count());

            // bind tags for ALL existing registries (idempotent; fills any remaining gaps)
            List<net.minecraft.core.Registry.PendingTags<?>> pending = TagLoader.loadTagsForExistingRegistries(rm, fullAccess);
            pending.forEach(p -> p.apply());
            dbg.println("step7: tags bound (" + pending.size() + " registries)");

            // bind data components onto holders
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(fullAccess)
                    .forEach(DataComponentInitializers.PendingComponents::apply);
            dbg.println("step8: components ok");

            try {
                CreativeModeTabs.tryRebuildTabContents(FeatureFlags.VANILLA_SET, true, fullAccess);
                dbg.println("step9: tabs rebuilt");
            } catch (Throwable t) {
                dbg.println("step9 REBUILD FAILED: " + t);
                t.printStackTrace(dbg);
            }

            // ---- items.json: full item registry with useful fields ----
            JsonArray itemsArr = new JsonArray();
            for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                JsonObject o = new JsonObject();
                o.addProperty("id", id.toString());
                o.addProperty("protocol_id", BuiltInRegistries.ITEM.getId(item));
                boolean isBlock = BuiltInRegistries.BLOCK.containsKey(id);
                o.addProperty("is_block", isBlock);
                String translationKey = (isBlock ? "block.minecraft." : "item.minecraft.") + id.getPath();
                o.addProperty("translation_key", translationKey);
                String localized = tr.apply(translationKey);
                if (localized != null && !langTag.isEmpty()) o.addProperty("name_" + langTag, localized);
                // 官方圖示: 依遊戲 item model 解析紋理路徑 → 本地抽出檔 (icon) + 來源 URL (icon_url)
                String texRoot = modelTexture(outDir, id.getPath());
                String imgFolder = null;
                String pngRel = null;
                if (texRoot != null && texRoot.indexOf('/') > 0) {
                    String folder = texRoot.substring(0, texRoot.indexOf('/'));
                    String texName = texRoot.substring(texRoot.indexOf('/') + 1);
                    if (Files.exists(outDir.resolve("img").resolve(folder).resolve(texName + ".png"))) {
                        imgFolder = folder;
                        pngRel = texName;
                    }
                }
                if (imgFolder == null) { // fallback: 直接同名 (+ waxed_ 剝除)
                    String f2 = findLocalIcon(outDir, id.getPath(), isBlock);
                    if (f2 == null && id.getPath().startsWith("waxed_")) {
                        f2 = findLocalIcon(outDir, id.getPath().substring(6), true);
                    }
                    if (f2 != null) {
                        imgFolder = f2;
                        pngRel = id.getPath().startsWith("waxed_")
                                && !Files.exists(outDir.resolve("img").resolve(f2)
                                        .resolve(id.getPath() + ".png"))
                                ? id.getPath().substring(6) : id.getPath();
                    }
                }
                if (imgFolder != null) {
                    o.addProperty("icon", "img/" + imgFolder + "/" + pngRel + ".png");
                    // icon_url: 僅在真的有逐張官方 CDN URL (舊版 asset index) 時給出
                    String u = iconUrl(assets,
                            "minecraft/textures/" + imgFolder + "/" + pngRel + ".png");
                    if (u != null) o.addProperty("icon_url", u);
                    // source: 官方 client jar URL (最新版紋理全部打包在 jar 內, CDN 無逐張圖) — 來源證明
                    if (nonEmpty(clientJarUrl)) o.addProperty("source", clientJarUrl);
                }
                DataComponentMap comps;
                try {
                    comps = item.components();
                } catch (Throwable t) {
                    comps = null;
                }
                if (comps != null) {
                    Integer ms = comps.get(DataComponents.MAX_STACK_SIZE);
                    if (ms != null) o.addProperty("max_stack_size", ms);
                    net.minecraft.world.item.Rarity r = comps.get(DataComponents.RARITY);
                    if (r != null) o.addProperty("rarity", r.name().toLowerCase());
                    Integer md = comps.get(DataComponents.MAX_DAMAGE);
                    if (md != null) o.addProperty("max_damage", md);
                }
                itemsArr.add(o);
            }
            Files.writeString(outDir.resolve("items.json"),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(itemsArr));
            dbg.println("WROTE items.json, items=" + itemsArr.size());

            // ---- creative-tabs.json: per-tab ordered grid + full stack data ----
            JsonArray tabs = new JsonArray();
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).toString());
                o.addProperty("display_name", tab.getDisplayName().getString());
                String tabKey = null;
                try {
                    net.minecraft.network.chat.Component c = tab.getDisplayName();
                    if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                        tabKey = tc.getKey();
                    }
                } catch (Throwable t) {
                    // non-translatable display name
                }
                String tabLocalized = tabKey != null ? tr.apply(tabKey) : null;
                if (tabLocalized != null && !langTag.isEmpty())
                    o.addProperty("display_name_" + langTag, tabLocalized);
                o.addProperty("type", tab.getType().name());
                try {
                    o.addProperty("icon", BuiltInRegistries.ITEM.getKey(tab.getIconItem().getItem()).toString());
                } catch (Throwable t) {
                    o.addProperty("icon", "");
                }
                JsonArray items = new JsonArray();
                JsonArray stacks = new JsonArray();
                for (ItemStack s : tab.getDisplayItems()) {
                    items.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                    stacks.add(stackJson(s, fullAccess));
                }
                o.add("items", items);
                o.add("stacks", stacks);
                JsonArray searchItems = new JsonArray();
                JsonArray searchStacks = new JsonArray();
                for (ItemStack s : tab.getSearchTabDisplayItems()) {
                    searchItems.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                    searchStacks.add(stackJson(s, fullAccess));
                }
                o.add("search_items", searchItems);
                o.add("search_stacks", searchStacks);
                tabs.add(o);
            }
            Files.writeString(outDir.resolve("creative-tabs.json"),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(tabs));
            dbg.println("WROTE creative-tabs.json, tabs=" + tabs.size());
        } catch (Throwable t) {
            dbg.println("FATAL: " + t);
            t.printStackTrace(dbg);
            throw t;
        } finally {
            dbg.flush();
            dbg.close();
        }
    }

    /** Stack as {id, components} — components = non-default DataComponentPatch (NBT variants preserved). */
    static JsonObject stackJson(ItemStack s, HolderLookup.Provider access) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        try {
            net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag> ops =
                    net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, access);
            net.minecraft.nbt.Tag tag = net.minecraft.core.component.DataComponentPatch.CODEC
                    .encodeStart(ops, s.getComponentsPatch()).getOrThrow();
            com.google.gson.JsonElement je =
                    net.minecraft.nbt.NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, tag);
            if (je.isJsonObject() && ((JsonObject) je).size() > 0) {
                o.add("components", (JsonObject) je);
            }
        } catch (Throwable t) {
            // leave components out
        }
        return o;
    }
}