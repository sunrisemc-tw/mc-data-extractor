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

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dumps the vanilla creative-mode tab layout and the full item registry for the
 * Minecraft version found inside the runtime server jar on the classpath.
 *
 * Boots the game exactly like a real server:
 *   bootstrap -> vanilla datapack -> tag loading -> worldgen registries ->
 *   data components -> CreativeModeTabs.tryRebuildTabContents
 *
 * Usage: java -cp "<runtime-server.jar>:<libraries>/*" DumpCreativeTabs [outputDir]
 * Writes items.json + creative-tabs.json (pretty-printed) into outputDir (default ".").
 */
public class DumpCreativeTabs {
    static PrintWriter dbg;

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        dbg = new PrintWriter(Files.newBufferedWriter(outDir.resolve("dump-debug.txt")));
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
                o.addProperty("translation_key",
                        (isBlock ? "block.minecraft." : "item.minecraft.") + id.getPath());
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