package mchorse.bbs_mod.particles.vanilla;

import com.google.gson.JsonParser;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleDescription;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Static particle samples shared by the type picker and form thumbnails. */
public class VanillaParticlePreview
{
    private static final Map<ResourceLocation, Optional<Preview>> PREVIEWS = new HashMap<>();

    public static void clearCache()
    {
        PREVIEWS.clear();
    }

    public static void render(UIContext context, ResourceLocation particle, float x, float y, float size, int fallbackColor)
    {
        if (size <= 0)
        {
            return;
        }

        Preview preview = PREVIEWS.computeIfAbsent(particle, VanillaParticlePreview::loadPreview).orElse(null);
        AbstractTexture texture = preview == null ? null : Minecraft.getInstance().getTextureManager().getTexture(preview.atlas());

        if (texture instanceof TextureAtlas atlas)
        {
            /* Resolve current UVs rather than keeping sprites across atlas reloads. */
            TextureAtlasSprite sprite = atlas.getSprite(preview.sprite());

            context.batcher.texturedBox(atlas.getId(), preview.color(), x, y, size, size,
                sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), 1, 1);
        }
        else
        {
            /* Custom model particles and modded types without a sprite still keep their slot. */
            context.batcher.scaledIcon(Icons.PARTICLE, fallbackColor, x, y, size);
        }
    }

    private static Optional<Preview> loadPreview(ResourceLocation particle)
    {
        int color = Colors.WHITE;

        if (particle.getNamespace().equals("minecraft"))
        {
            /* These types use block/item models or emit another particle instead of owning sprites.
             * Argument-dependent types use a representative sample, without changing form settings. */
            String blockSprite = switch (particle.getPath())
            {
                case "block" -> "block/stone";
                case "block_marker" -> "item/barrier";
                case "item", "item_snowball" -> "item/snowball";
                case "item_slime" -> "item/slime_ball";
                default -> null;
            };

            if (blockSprite != null)
            {
                return Optional.of(new Preview(TextureAtlas.LOCATION_BLOCKS, ResourceLocation.parse(blockSprite), color));
            }

            color = sampleColor(particle.getPath());

            if (particle.getPath().equals("explosion_emitter"))
            {
                particle = ResourceLocation.parse("explosion");
            }
            else if (particle.getPath().equals("gust_emitter"))
            {
                particle = ResourceLocation.parse("gust");
            }
        }

        ResourceLocation definition = ResourceLocation.fromNamespaceAndPath(particle.getNamespace(), "particles/" + particle.getPath() + ".json");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(definition);

        if (resource.isPresent())
        {
            try (Reader reader = resource.get().openAsReader())
            {
                List<ResourceLocation> textures = ParticleDescription.fromJson(JsonParser.parseReader(reader).getAsJsonObject()).getTextures();

                if (textures != null && !textures.isEmpty())
                {
                    /* A middle frame is readable even for effects which start with an empty frame. */
                    return Optional.of(new Preview(TextureAtlas.LOCATION_PARTICLES, textures.get(textures.size() / 2), color));
                }
            }
            catch (IOException | RuntimeException e)
            {
                /* A missing or malformed resource-pack definition must not prevent picking a type. */
            }
        }

        return Optional.empty();
    }

    private static int sampleColor(String particle)
    {
        /* Vanilla applies these tints in particle factories, not in the texture definitions.
         * Parameterized/random colors are representative defaults for the type picker. */
        return switch (particle)
        {
            case "dust" -> 0xFFFF3030;
            case "dust_color_transition" -> 0xFF55CCBB;
            case "falling_dust" -> 0xFFDBCAA0;
            case "smoke", "large_smoke", "ash" -> 0xFF888888;
            case "dust_plume" -> 0xFFBCA795;
            case "dripping_water", "falling_water", "dripping_dripstone_water", "falling_dripstone_water",
                "rain", "splash", "fishing" -> 0xFF4060FF;
            case "dripping_lava", "falling_lava", "landing_lava", "dripping_dripstone_lava", "falling_dripstone_lava" -> 0xFFFF6600;
            case "dripping_honey", "falling_honey", "landing_honey", "falling_nectar" -> 0xFFFFB52E;
            case "dripping_obsidian_tear", "falling_obsidian_tear", "landing_obsidian_tear" -> 0xFF8245E6;
            case "portal", "reverse_portal", "dragon_breath", "witch" -> 0xFFB34DE6;
            case "happy_villager", "composter", "totem_of_undying" -> 0xFF66CC33;
            case "sneeze" -> 0xFFB3C733;
            case "falling_spore_blossom", "spore_blossom_air" -> 0xFF519B24;
            case "crimson_spore" -> 0xFFE66666;
            case "warped_spore" -> 0xFF19B3A6;
            case "mycelium" -> 0xFFAA88AA;
            case "underwater", "dolphin" -> 0xFF6699FF;
            case "squid_ink" -> 0xFF333344;
            case "glow_squid_ink", "glow", "scrape" -> 0xFF66DDCC;
            case "wax_on" -> 0xFFFF994D;
            case "note" -> 0xFF66CC33;
            default -> Colors.WHITE;
        };
    }

    private record Preview(ResourceLocation atlas, ResourceLocation sprite, int color)
    {}
}
