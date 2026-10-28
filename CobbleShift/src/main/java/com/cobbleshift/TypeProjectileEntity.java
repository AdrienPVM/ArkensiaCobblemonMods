package com.cobbleshift;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.thrown.ThrownEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.EntityDimensions;

/**
 * Type-based projectile entity for transformed player attacks.
 * Renders as a FlyingItemEntity (uses getStack() for the visual item).
 * Applies on-hit effects based on the Pokémon type.
 */
public class TypeProjectileEntity extends ThrownEntity implements FlyingItemEntity {

    public static final EntityType<TypeProjectileEntity> TYPE = Registry.register(
        Registries.ENTITY_TYPE,
        Identifier.of("cobbleshift", "type_projectile"),
        FabricEntityTypeBuilder.<TypeProjectileEntity>create(SpawnGroup.MISC, TypeProjectileEntity::new)
            .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
            .trackRangeChunks(4)
            .trackedUpdateRate(10)
            .build()
    );

    private static final TrackedData<String> POKEMON_TYPE =
        DataTracker.registerData(TypeProjectileEntity.class, TrackedDataHandlerRegistry.STRING);

    private float attackDamage = 4.0f;

    public TypeProjectileEntity(EntityType<? extends ThrownEntity> type, World world) {
        super(type, world);
    }

    public TypeProjectileEntity(World world, LivingEntity owner, String pokemonType, float damage) {
        super(TYPE, world);
        this.setOwner(owner);
        // Position is set by AttackHandler after construction — don't override here
        this.attackDamage = damage;
        this.dataTracker.set(POKEMON_TYPE, pokemonType != null ? pokemonType : "normal");
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(POKEMON_TYPE, "normal");
    }

    public String getPokemonType() {
        return this.dataTracker.get(POKEMON_TYPE);
    }

    @Override
    public ItemStack getStack() {
        return getItemForType(getPokemonType());
    }

    @Override
    protected void onEntityHit(EntityHitResult entityHitResult) {
        super.onEntityHit(entityHitResult);
        if (this.getWorld().isClient) return;

        var target = entityHitResult.getEntity();
        // Don't damage the owner
        if (target.equals(this.getOwner())) return;

        // Apply base damage
        target.damage(this.getDamageSources().thrown(this, this.getOwner()), attackDamage);

        // Apply on-hit effect based on type
        if (target instanceof LivingEntity living) {
            applyOnHitEffect(living, getPokemonType());
        }

        this.discard();
    }

    @Override
    protected void onCollision(HitResult hitResult) {
        super.onCollision(hitResult);
        if (!this.getWorld().isClient) {
            this.discard();
        }
    }

    @Override
    protected double getGravity() {
        return 0.01; // Near-straight flight
    }

    /**
     * Apply a type-specific on-hit effect.
     */
    private void applyOnHitEffect(LivingEntity target, String type) {
        if (type == null) return;
        switch (type.toLowerCase()) {
            case "fire" -> target.setOnFireFor(3);
            case "ice" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
            case "poison" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 0));
            case "electric" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0));
            case "psychic" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 30, 0));
            case "ghost" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
            case "dark" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0));
            case "dragon" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0));
            case "fairy" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 60, 0));
            case "grass" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 0));
            case "water" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 0));
            case "fighting" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 0));
            case "ground" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
            case "rock" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 60, 0));
            case "steel" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 40, 0));
            case "bug" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 80, 0));
            case "flying" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 20, 0));
            // normal → no special effect
        }
    }

    /**
     * Get a representative MC item for each Pokémon type (used for FlyingItemEntityRenderer).
     */
    private static ItemStack getItemForType(String type) {
        if (type == null) return new ItemStack(Items.SNOWBALL);
        return new ItemStack(switch (type.toLowerCase()) {
            case "fire" -> Items.FIRE_CHARGE;
            case "water" -> Items.HEART_OF_THE_SEA;
            case "ice" -> Items.SNOWBALL;
            case "electric" -> Items.GLOWSTONE_DUST;
            case "grass" -> Items.FERN;
            case "poison" -> Items.SPIDER_EYE;
            case "psychic" -> Items.ENDER_PEARL;
            case "ghost" -> Items.GHAST_TEAR;
            case "dragon" -> Items.DRAGON_BREATH;
            case "dark" -> Items.COAL;
            case "fairy" -> Items.AMETHYST_SHARD;
            case "ground" -> Items.CLAY_BALL;
            case "rock" -> Items.COBBLESTONE;
            case "steel" -> Items.IRON_NUGGET;
            case "fighting" -> Items.BRICK;
            case "flying" -> Items.FEATHER;
            case "bug" -> Items.SLIME_BALL;
            default -> Items.SNOWBALL;
        });
    }
}
