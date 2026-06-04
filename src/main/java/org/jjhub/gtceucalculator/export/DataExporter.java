package org.jjhub.gtceucalculator.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.tags.ITagManager;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Mod.EventBusSubscriber(modid = ExportMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DataExporter {

    private static final Logger LOG  = LogManager.getLogger(ExportMod.MOD_ID);
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("gtceuexport")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("catalog")
                    .executes(DataExporter::runExportCatalog))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("icons")
                    .executes(DataExporter::runExportIcons))
        );
    }

    private static int runExportCatalog(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                Path outDir = FMLPaths.GAMEDIR.get().resolve("gtceu_calculator_export");
                Files.createDirectories(outDir);
                Map<String, Map<String, Object>> catalog = new TreeMap<>();
                catalog.put("items", new TreeMap<String, Object>());
                catalog.put("recipes", new TreeMap<String, Object>());
                collectItems(catalog.get("items"));
                collectFluids(catalog.get("items"));
                collectRecipes(catalog.get("recipes"), mc);
                try (java.io.Writer w = Files.newBufferedWriter(outDir.resolve("catalog.json"))) {
                    GSON.toJson(catalog, w);
                }
                LOG.info("[gtceu_calculator_export] catalog.json → {}", outDir.toAbsolutePath());
            } catch (Exception e) {
                LOG.error("[gtceu_calculator_export] Catalog export failed", e);
            }
        });
        return 1;
    }

    private static int runExportIcons(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                Path outDir = FMLPaths.GAMEDIR.get().resolve("gtceu_calculator_export");
                Files.createDirectories(outDir);
                exportIcons(outDir, mc);
                exportFluidIcons(outDir, mc);
                LOG.info("[gtceu_calculator_export] Icons → {}", outDir.toAbsolutePath());
            } catch (Exception e) {
                LOG.error("[gtceu_calculator_export] Icons export failed", e);
            }
        });
        return 1;
    }

    private static void collectItems(Map<String, Object> items) throws IOException {
        Map<String, List<String>> tagsByItem = invertTagMap(ForgeRegistries.ITEMS);

        ForgeRegistries.ITEMS.forEach(item -> {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || "minecraft:air".equals(id.toString())) return;

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", new ItemStack(item).getHoverName().getString());
            info.put("tags", tagsByItem.getOrDefault(id.toString(), List.of()));
            items.put(id.toString(), info);
        });

        LOG.info("[gtceu_calculator_export] items.json — {} items", items.size());
    }

    private static void collectFluids(Map<String, Object> fluids) throws IOException {
        Map<String, List<String>> tagsByFluid = invertTagMap(ForgeRegistries.FLUIDS);

        ForgeRegistries.FLUIDS.forEach(fluid -> {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null || "minecraft:empty".equals(id.toString())) return;

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", fluid.getFluidType().getDescription().getString());
            info.put("tags", tagsByFluid.getOrDefault(id.toString(), List.of()));
            fluids.put(id.toString(), info);
        });

        LOG.info("[gtceu_calculator_export] fluids.json — {} fluids", fluids.size());
    }

    // ── Recipes ──────────────────────────────────────────────────────────────
    // Output: recipes.json — array of { "id", "type", "data": <full serialized recipe> }
    // Falls back to { "output", "inputs" } for serializers without a codec.

    private static void collectRecipes(Map<String, Object> recipes, Minecraft mc) throws IOException {
        if (mc.getConnection() == null) {
            LOG.warn("[gtceu_calculator_export] No connection — skipping recipe export");
            return;
        }

        for (Recipe<?> recipe : mc.getConnection().getRecipeManager().getRecipes()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", recipe.getId().toString());

            // Prefer the RecipeType registry key (gives specific machine type for GTCEu,
            // e.g. "gtceu:macerator" rather than the generic serializer key "gtceu:machine").
            ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            if (typeKey == null)
                typeKey = ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer());
            r.put("type", typeKey != null ? typeKey.toString() : "unknown");

            boolean handledAsGT = tryExportAsGTRecipe(r, recipe);
            if (!handledAsGT) {
                // Standard MC recipe fallback (shaped, shapeless, smelting, …)
                try {
                    ItemStack out = recipe.getResultItem(mc.level != null
                        ? mc.level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY);
                    if (!out.isEmpty()) {
                        Map<String, Object> output = new LinkedHashMap<>();
                        output.put("item",  Objects.toString(ForgeRegistries.ITEMS.getKey(out.getItem())));
                        output.put("count", out.getCount());
                        r.put("output", output);
                    }
                    List<List<String>> inputs = new ArrayList<>();
                    recipe.getIngredients().forEach(ing -> {
                        List<String> opts = new ArrayList<>();
                        extractIngredientRefs(ing.toJson(), opts);
                        if (!opts.isEmpty()) inputs.add(opts);
                    });
                    r.put("inputs", inputs);
                } catch (Exception ignored) {}
            }

            recipes.put(recipe.getId().toString(), r);
        }

        LOG.info("[gtceu_calculator_export] recipes.json — {} recipes", recipes.size());
    }

    
    /**
     * Attempts to populate {@code r} with GTCEu-specific recipe fields.
     *
     * @return {@code true} if {@code recipe} is a {@code GTRecipe};
     *         {@code false} if it is a standard MC recipe.
     */
    private static boolean tryExportAsGTRecipe(Map<String, Object> r, Recipe<?> recipe) {
        if (!(recipe instanceof GTRecipe gt)) return false;

        r.put("duration", gt.duration);

        // EU/t via getInputEUt() — returns EnergyStack(voltage, amperage).
        // For consumer recipes (crafting machines): inputEut is set, outputEut is empty.
        // For generator recipes (turbines, combustion, etc.): inputEut is empty, outputEut is set.
        // We export outputEut as a negative eut so recipe.py can detect the sign.
        EnergyStack inputEut = gt.getInputEUt();
        if (!inputEut.isEmpty()) {
            r.put("eut", inputEut.voltage());
        } else {
            EnergyStack outputEut = gt.getOutputEUt();
            if (!outputEut.isEmpty()) r.put("eut", -outputEut.voltage());
        }

        // Item inputs → [{"refs":[ref, …], "count":N}, …]
        List<Map<String, Object>> itemInputSlots = new ArrayList<>();
        for (Content ct : gt.inputs.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
            Ingredient ing = (Ingredient) ct.content;
            int count = (ing instanceof SizedIngredient s) ? s.getAmount() : 1;
            List<String> refs = ingredientRefs(ing);
            if (refs.isEmpty()) continue;
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("refs", refs);
            if (count != 1) slot.put("count", count);
            if (ct.isChanced()) slot.put("chance", (double) ct.chance / ct.maxChance);
            itemInputSlots.add(slot);
        }
        r.put("inputs", itemInputSlots);

        // Fluid inputs → [{"fluid","mb"}, …]
        List<Map<String, Object>> fluidInputs = new ArrayList<>();
        for (Content ct : gt.inputs.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
            Map<String, Object> m = fluidToMap((FluidIngredient) ct.content, ct.chance, ct.maxChance);
            if (m != null) fluidInputs.add(m);
        }
        if (!fluidInputs.isEmpty()) r.put("fluid_inputs", fluidInputs);

        // Item outputs → [{"item","count",?"chance"}, …]
        List<Map<String, Object>> itemOutputs = new ArrayList<>();
        for (Content ct : gt.outputs.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
            Ingredient ing = (Ingredient) ct.content;
            int count = (ing instanceof SizedIngredient s) ? s.getAmount() : 1;
            List<String> refs = ingredientRefs(ing);
            if (refs.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", refs.get(0));
            if (count != 1) m.put("count", count);
            if (ct.isChanced())
                m.put("chance", (double) ct.chance / ct.maxChance);
            itemOutputs.add(m);
        }
        if (!itemOutputs.isEmpty()) r.put("outputs", itemOutputs);

        // Fluid outputs → [{"fluid","mb",?"chance"}, …]
        List<Map<String, Object>> fluidOutputs = new ArrayList<>();
        for (Content ct : gt.outputs.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
            Map<String, Object> m = fluidToMap((FluidIngredient) ct.content, ct.chance, ct.maxChance);
            if (m != null) fluidOutputs.add(m);
        }
        if (!fluidOutputs.isEmpty()) r.put("fluid_outputs", fluidOutputs);

        return true;
    }

    // ── GTCEu helpers ──────────────────────────────────────────────────────────────

    /**
     * Extracts item/tag refs from an ingredient, unwrapping {@code SizedIngredient} first.
     * Uses {@link #extractIngredientRefs} on the inner Ingredient's JSON.
     */
    private static List<String> ingredientRefs(Ingredient ing) {
        Ingredient base = (ing instanceof SizedIngredient s) ? s.getInner() : ing;
        List<String> refs = new ArrayList<>();
        extractIngredientRefs(base.toJson(), refs);
        return refs;
    }
    
    /**
     * Converts a {@link FluidIngredient} to a {"fluid"/"tag", "mb", ?"chance"} map.
     * {@code FluidIngredient.toJson()} format: {@code {"amount":mb, "value":[{"fluid":"ns:id"}]}}.
     * @param chance    raw int chance (0–maxChance)
     * @param maxChance denominator for the chance fraction (typically 10000)
     */
    private static Map<String, Object> fluidToMap(FluidIngredient fi, int chance, int maxChance) {
        try {
            JsonObject json = fi.toJson().getAsJsonObject();
            long mb = json.get("amount").getAsLong();

            // "value" is always a JsonArray in FluidIngredient.toJson() (see source).
            JsonElement valElem = json.get("value");
            JsonObject valueObj;
            if (valElem != null && valElem.isJsonArray() && valElem.getAsJsonArray().size() > 0) {
                valueObj = valElem.getAsJsonArray().get(0).getAsJsonObject();
            } else if (valElem != null && valElem.isJsonObject()) {
                valueObj = valElem.getAsJsonObject();
            } else {
                return null;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            if      (valueObj.has("fluid")) m.put("fluid", valueObj.get("fluid").getAsString());
            else if (valueObj.has("tag"))   m.put("tag",   "#" + valueObj.get("tag").getAsString());
            else return null;
            m.put("mb", mb);
            if (chance < maxChance)
                m.put("chance", (double) chance / maxChance);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Icons ────────────────────────────────────────────────────────────────
    // Output: icons/<namespace>/<path>.png — 32×32 RGBA PNGs rendered in-game.

    private static void exportIcons(Path outDir, Minecraft mc) throws IOException {
        Path iconsDir = outDir.resolve("icons");
        Files.createDirectories(iconsDir);

        final int SIZE = 32;
        RenderTarget fbo = new MainTarget(SIZE, SIZE);
        Matrix4f savedProj = setupRenderContext(SIZE);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int count = 0;

        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) continue;
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) continue; // skip minecraft:air

            Path iconPath = iconsDir.resolve(id.getNamespace()).resolve(id.getPath() + ".png");
            renderStackToFile(stack, iconPath, fbo, buffers, SIZE, mc);
            count++;
        }

        restoreRenderContext(savedProj, fbo, mc);
        LOG.info("[gtceu_calculator_export] icons/ — {} item icons", count);
    }

    // ── Fluid icons ──────────────────────────────────────────────────────────
    // Renders each fluid's still texture as a tinted square.
    // Saved at icons/<fluid-ns>/<fluid-path>.png — same path scheme as items.

    private static void exportFluidIcons(Path outDir, Minecraft mc) throws IOException {
        Path iconsDir = outDir.resolve("icons");
        Files.createDirectories(iconsDir);

        final int SIZE = 32;
        RenderTarget fbo = new MainTarget(SIZE, SIZE);
        Matrix4f savedProj = setupRenderContext(SIZE);
        int count = 0, skipped = 0;

        for (Fluid fluid : ForgeRegistries.FLUIDS) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null || "minecraft:empty".equals(id.toString())) continue;

            try {
                IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
                ResourceLocation stillLoc = ext.getStillTexture();
                if (stillLoc == null) { skipped++; continue; }

                TextureAtlasSprite sprite =
                    mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(stillLoc);

                // ARGB tint — default 0xFFFFFFFF (white = no tint).
                int tint = ext.getTintColor();
                float ta = ((tint >> 24) & 0xFF) / 255f;
                float tr = ((tint >> 16) & 0xFF) / 255f;
                float tg = ((tint >>  8) & 0xFF) / 255f;
                float tb = ( tint        & 0xFF) / 255f;
                if (ta == 0f) ta = 1f; // some mods store 0 alpha as "opaque"

                fbo.setClearColor(0f, 0f, 0f, 0f);
                fbo.clear(Minecraft.ON_OSX);
                fbo.bindWrite(true);

                // Draw a SIZE×SIZE textured, tinted quad.
                RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();

                float u0 = sprite.getU0(), u1 = sprite.getU1();
                float v0 = sprite.getV0(), v1 = sprite.getV1();
                Matrix4f mat = new PoseStack().last().pose(); // identity local transform

                BufferBuilder bb = Tesselator.getInstance().getBuilder();
                bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                bb.vertex(mat,    0, SIZE, 0).uv(u0, v1).color(tr, tg, tb, ta).endVertex();
                bb.vertex(mat, SIZE, SIZE, 0).uv(u1, v1).color(tr, tg, tb, ta).endVertex();
                bb.vertex(mat, SIZE,    0, 0).uv(u1, v0).color(tr, tg, tb, ta).endVertex();
                bb.vertex(mat,    0,    0, 0).uv(u0, v0).color(tr, tg, tb, ta).endVertex();
                Tesselator.getInstance().end();

                fbo.unbindWrite();

                NativeImage img = new NativeImage(SIZE, SIZE, false);
                RenderSystem.bindTexture(fbo.getColorTextureId());
                img.downloadTexture(0, false);
                img.flipY();

                Path iconPath = iconsDir.resolve(id.getNamespace()).resolve(id.getPath() + ".png");
                Files.createDirectories(iconPath.getParent());
                img.writeToFile(iconPath);
                img.close();
                count++;
            } catch (Exception e) {
                LOG.debug("[gtceu_calculator_export] Fluid icon skipped for {}: {}", id, e.getMessage());
                skipped++;
            }
        }

        restoreRenderContext(savedProj, fbo, mc);
        LOG.info("[gtceu_calculator_export] icons/ — {} fluid icons ({} skipped)", count, skipped);
    }

    // ── Shared render helpers ─────────────────────────────────────────────────

    private static Matrix4f setupRenderContext(int size) {
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(
            new Matrix4f().setOrtho(0, size, size, 0, 1000, 21000),
            VertexSorting.ORTHOGRAPHIC_Z
        );
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0.0, 0.0, -2000.0);
        RenderSystem.applyModelViewMatrix();
        return savedProj;
    }

    private static void restoreRenderContext(Matrix4f savedProj, RenderTarget fbo, Minecraft mc) {
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorting.ORTHOGRAPHIC_Z);
        fbo.destroyBuffers();
        mc.getMainRenderTarget().bindWrite(true);
    }

    private static void renderStackToFile(
        ItemStack stack, Path iconPath,
        RenderTarget fbo, MultiBufferSource.BufferSource buffers,
        int size, Minecraft mc
    ) throws IOException {
        fbo.setClearColor(0f, 0f, 0f, 0f);
        fbo.clear(Minecraft.ON_OSX);
        fbo.bindWrite(true);

        GuiGraphics gfx = new GuiGraphics(mc, buffers);
        gfx.pose().scale((float) size / 16, (float) size / 16, 1.0f);
        gfx.renderItem(stack, 0, 0);
        gfx.flush();
        fbo.unbindWrite();

        NativeImage img = new NativeImage(size, size, false);
        RenderSystem.bindTexture(fbo.getColorTextureId());
        img.downloadTexture(0, false);
        img.flipY();
        Files.createDirectories(iconPath.getParent());
        img.writeToFile(iconPath);
        img.close();
    }

    // ── Utilities ────────────────────────────────────────────────────────────


    /**
     * Recursively extracts {@code "namespace:id"} or {@code "#namespace:tag"} strings
     * from an ingredient JsonElement.
     * Handles: plain {@code {"item":…}}/{"tag":…}, arrays of alternatives, and
     * GTCEu SizedIngredient format {@code {"count":N, "ingredient":{…}}}.
     */
    private static void extractIngredientRefs(JsonElement json, List<String> out) {
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(el -> {
                if (el.isJsonObject()) extractIngredientRefs(el, out);
            });
        } else if (json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            if (obj.has("ingredient")) {
                // SizedIngredient wrapper: {"count": N, "ingredient": {…}}
                extractIngredientRefs(obj.get("ingredient"), out);
            } else if (obj.has("tag")) {
                out.add("#" + obj.get("tag").getAsString());
            } else if (obj.has("item")) {
                out.add(obj.get("item").getAsString());
            }
        }
    }

    // Inverts the tag registry: value_id → sorted list of tag_ids.
    private static <T> Map<String, List<String>> invertTagMap(IForgeRegistry<T> registry) {
        ITagManager<T> mgr = registry.tags();
        Map<String, List<String>> result = new HashMap<>();
        if (mgr == null) return result;

        mgr.stream().forEach(tag -> {
            String tagId = tag.getKey().location().toString();
            tag.stream().forEach(value -> {
                ResourceLocation key = registry.getKey(value);
                if (key != null)
                    result.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(tagId);
            });
        });

        result.values().forEach(Collections::sort);
        return result;
    }
}
