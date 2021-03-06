package mcjty.rftools;

import mcjty.lib.varia.GlobalCoordinate;
import mcjty.lib.varia.WrenchChecker;
import mcjty.rftools.blocks.blockprotector.BlockProtectorTileEntity;
import mcjty.rftools.blocks.blockprotector.BlockProtectors;
import mcjty.rftools.blocks.environmental.NoTeleportAreaManager;
import mcjty.rftools.blocks.environmental.PeacefulAreaManager;
import mcjty.rftools.blocks.screens.ScreenSetup;
import mcjty.rftools.blocks.teleporter.TeleportDestination;
import mcjty.rftools.blocks.teleporter.TeleportationTools;
import mcjty.rftools.playerprops.BuffProperties;
import mcjty.rftools.playerprops.FavoriteDestinationsProperties;
import mcjty.rftools.playerprops.PlayerExtendedProperties;
import mcjty.rftools.playerprops.PropertiesDispatcher;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ForgeEventHandlers {

    // Workaround for the charged porter so that the teleport can be done outside
    // of the entity tick loop
    private static List<Pair<TeleportDestination,EntityPlayer>> playersToTeleportHere = new ArrayList<>();

    public static void addPlayerToTeleportHere(TeleportDestination destination, EntityPlayer player) {
        playersToTeleportHere.add(Pair.of(destination, player));
    }

    private static void performDelayedTeleports() {
        if (!playersToTeleportHere.isEmpty()) {
            // Teleport players here
            for (Pair<TeleportDestination, EntityPlayer> pair : playersToTeleportHere) {
                TeleportationTools.performTeleport(pair.getRight(), pair.getLeft(), 0, 10, false);
            }
            playersToTeleportHere.clear();
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START && event.world.provider.getDimension() == 0) {
            performDelayedTeleports();
        }
    }


    @SubscribeEvent
    public void onPlayerTickEvent(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !event.player.worldObj.isRemote) {
            BuffProperties buffProperties = PlayerExtendedProperties.getBuffProperties(event.player);
            if (buffProperties != null) {
                buffProperties.tickBuffs((EntityPlayerMP) event.player);
            }
        }
    }

    @SubscribeEvent
    public void onEntityConstructing(AttachCapabilitiesEvent.Entity event){
        if (event.getEntity() instanceof EntityPlayer) {
            if (!event.getEntity().hasCapability(PlayerExtendedProperties.BUFF_CAPABILITY, null)) {
                event.addCapability(new ResourceLocation(RFTools.MODID, "Properties"), new PropertiesDispatcher());
            }
        }
    }


    @SubscribeEvent
    public void onPlayerInteractEvent(PlayerInteractEvent event) {
        if (event instanceof PlayerInteractEvent.LeftClickBlock) {
            checkCreativeClick(event);
        }

        ItemStack heldItem = event.getEntityPlayer().getHeldItem(event.getHand());
        if (heldItem == null || heldItem.getItem() == null) {
            return;
        }
        if (event.getEntityPlayer().isSneaking() && WrenchChecker.isAWrench(heldItem.getItem())) {
            // If the block is protected we prevent sneak-wrenching it.
            World world = event.getWorld();
            int x = event.getPos().getX();
            int y = event.getPos().getY();
            int z = event.getPos().getZ();
            Collection<GlobalCoordinate> protectors = BlockProtectors.getProtectors(world, x, y, z);
            if (BlockProtectors.checkHarvestProtection(x, y, z, world, protectors)) {
                event.setCanceled(true);
            }
        }

    }

    @SubscribeEvent
    public void onBlockBreakEvent(BlockEvent.BreakEvent event) {
        int x = event.getPos().getX();
        int y = event.getPos().getY();
        int z = event.getPos().getZ();
        World world = event.getWorld();

        Collection<GlobalCoordinate> protectors = BlockProtectors.getProtectors(world, x, y, z);
        if (BlockProtectors.checkHarvestProtection(x, y, z, world, protectors)) {
            event.setCanceled(true);
        }
    }

    private void checkCreativeClick(PlayerInteractEvent event) {
        if (event.getEntityPlayer().isCreative()) {
            // In creative we don't want our screens to be destroyed by left click unless he/she is sneaking
            Block block = event.getWorld().getBlockState(event.getPos()).getBlock();
            if (block == ScreenSetup.screenBlock || block == ScreenSetup.creativeScreenBlock || block == ScreenSetup.screenHitBlock) {
                if (!event.getEntityPlayer().isSneaking()) {
                    // If not sneaking while we hit a screen we cancel the destroy. Otherwise we go through.

                    if (event.getWorld().isRemote) {
                        // simulate click because it isn't called in creativemode or when we cancel the event
                        block.onBlockClicked(event.getWorld(), event.getPos(), event.getEntityPlayer());
                    }

                    event.setCanceled(true);
                }
            }
        }
    }


    @SubscribeEvent
    public void onDetonate(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        Vec3d explosionVector = explosion.getPosition();
        Collection<GlobalCoordinate> protectors = BlockProtectors.getProtectors(event.getWorld(), (int) explosionVector.xCoord, (int) explosionVector.yCoord, (int) explosionVector.zCoord);

        if (protectors.isEmpty()) {
            return;
        }

        List<BlockPos> affectedBlocks = event.getAffectedBlocks();
        List<BlockPos> toremove = new ArrayList<>();

        int rf = 0;
        for (GlobalCoordinate protector : protectors) {
            BlockPos pos = protector.getCoordinate();
            TileEntity te = event.getWorld().getTileEntity(pos);
            if (te instanceof BlockProtectorTileEntity) {
                BlockProtectorTileEntity blockProtectorTileEntity = (BlockProtectorTileEntity) te;
                for (BlockPos block : affectedBlocks) {
                    BlockPos relative = blockProtectorTileEntity.absoluteToRelative(block);
                    boolean b = blockProtectorTileEntity.isProtected(relative);
                    if (b) {
                        Vec3d blockVector = new Vec3d(block);
                        double distanceTo = explosionVector.distanceTo(blockVector);
                        int rfneeded = blockProtectorTileEntity.attemptExplosionProtection((float) (distanceTo / explosion.explosionSize), explosion.explosionSize);
                        if (rfneeded > 0) {
                            toremove.add(block);
                            rf += rfneeded;
                        } else {
                            blockProtectorTileEntity.removeProtection(relative);
                        }
                    }
                }
            }
        }

        affectedBlocks.removeAll(toremove);
    }

    @SubscribeEvent
    public void onEntityTeleport(EnderTeleportEvent event) {
        World world = event.getEntity().getEntityWorld();
        int id = world.provider.getDimension();

        Entity entity = event.getEntity();
        BlockPos coordinate = new BlockPos((int) entity.posX, (int) entity.posY, (int) entity.posZ);
        if (NoTeleportAreaManager.isTeleportPrevented(entity, new GlobalCoordinate(coordinate, id))) {
            event.setCanceled(true);
        } else {
            coordinate = new BlockPos((int) event.getTargetX(), (int) event.getTargetY(), (int) event.getTargetZ());
            if (NoTeleportAreaManager.isTeleportPrevented(entity, new GlobalCoordinate(coordinate, id))) {
                event.setCanceled(true);
            }
        }
    }


    @SubscribeEvent
    public void onEntitySpawnEvent(LivingSpawnEvent.CheckSpawn event) {
        World world = event.getWorld();
        int id = world.provider.getDimension();

        Entity entity = event.getEntity();
        if (entity instanceof IMob) {
            BlockPos coordinate = new BlockPos((int) entity.posX, (int) entity.posY, (int) entity.posZ);
            if (PeacefulAreaManager.isPeaceful(new GlobalCoordinate(coordinate, id))) {
                event.setResult(Event.Result.DENY);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerCloned(PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            // We need to copyFrom the capabilities
            if (event.getOriginal().hasCapability(PlayerExtendedProperties.FAVORITE_DESTINATIONS_CAPABILITY, null)) {
                FavoriteDestinationsProperties oldFavorites = event.getOriginal().getCapability(PlayerExtendedProperties.FAVORITE_DESTINATIONS_CAPABILITY, null);
                FavoriteDestinationsProperties newFavorites = PlayerExtendedProperties.getFavoriteDestinations(event.getEntityPlayer());
                newFavorites.copyFrom(oldFavorites);
            }
        }
    }

}
