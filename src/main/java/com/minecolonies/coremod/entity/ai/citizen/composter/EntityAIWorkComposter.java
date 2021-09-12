package com.minecolonies.coremod.entity.ai.citizen.composter;

import com.minecolonies.api.colony.requestsystem.requestable.StackList;
import com.minecolonies.api.crafting.ItemStackHandling;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.colony.buildings.modules.ItemListModule;
import com.minecolonies.coremod.colony.buildings.workerbuildings.BuildingComposter;
import com.minecolonies.coremod.colony.jobs.JobComposter;
import com.minecolonies.coremod.entity.ai.basic.AbstractEntityAIInteract;
import com.minecolonies.coremod.tileentities.TileEntityBarrel;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.research.util.ResearchConstants.PODZOL_CHANCE;
import static com.minecolonies.api.util.constant.Constants.DOUBLE;
import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;
import static com.minecolonies.api.util.constant.TranslationConstants.*;

public class EntityAIWorkComposter extends AbstractEntityAIInteract<JobComposter, BuildingComposter>
{
    /**
     * Base xp gain for the composter.
     */
    private static final double BASE_XP_GAIN = 1;

    /**
     * The block pos to which the AI is going.
     */
    private BlockPos currentTarget;

    /**
     * The number of times the AI will check if the player has set any items on the list until messaging him
     */
    private static final int TICKS_UNTIL_COMPLAIN = 12000;

    /**
     * The ticks elapsed since the last complain
     */
    private int ticksToComplain = 0;

    /**
     * Number of ticks that the AI should wait before deciding again
     */
    private static final int DECIDE_DELAY = 40;

    /**
     * Number of ticks that the AI should wait after completing a task
     */
    private static final int AFTER_TASK_DELAY = 5;

    /**
     * Id in compostable map for list.
     */
    public static final String COMPOSTABLE_LIST = "compostables";

    /**
     * Composting icon
     */
    private final static VisibleCitizenStatus COMPOST =
      new VisibleCitizenStatus(new ResourceLocation(Constants.MOD_ID, "textures/icons/work/composter.png"), "com.minecolonies.gui.visiblestatus.composter");

    /**
     * Constructor for the AI
     *
     * @param job the job to fulfill
     */
    public EntityAIWorkComposter(@NotNull final JobComposter job)
    {
        super(job);
        super.registerTargets(
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::accelerateBarrels, TICKS_SECOND),
          new AITarget(IDLE, START_WORKING, 1),
          new AITarget(GET_MATERIALS, this::getMaterials, TICKS_SECOND),
          new AITarget(START_WORKING, this::decideWhatToDo, 1),
          new AITarget(COMPOSTER_FILL, this::fillBarrels, 10),
          new AITarget(COMPOSTER_HARVEST, this::harvestBarrels, 10)
        );
        worker.setCanPickUpLoot(true);
    }

    /**
     * Actually accelerate the Barrels
     */
    private IAIState accelerateBarrels()
    {
        final int accelerationTicks = (worker.getCitizenData().getCitizenSkillHandler().getLevel(getOwnBuilding().getPrimarySkill()) / 10) * 2;
        final World world = getOwnBuilding().getColony().getWorld();
        for (final BlockPos pos : getOwnBuilding().getBarrels())
        {
            if (WorldUtil.isBlockLoaded(world, pos))
            {
                final TileEntity entity = world.getBlockEntity(pos);
                if (entity instanceof TileEntityBarrel)
                {
                    final TileEntityBarrel barrel = (TileEntityBarrel) entity;
                    for(int i = 0; i < accelerationTicks; i++)
                    {
                        if (barrel.checkIfWorking() && !barrel.isDone())
                        {
                            barrel.tick();
                        }
                    }
                }
            }
        }
        return null;
    }


    /**
     * Method for the AI to try to get the materials needed for the task he's doing. Will request if there are no materials
     *
     * @return the new IAIState after doing this
     */
    private IAIState getMaterials()
    {
        if (walkToBuilding())
        {
            setDelay(2);
            return getState();
        }

        final List<ItemStorage> list = getOwnBuilding().getModuleMatching(ItemListModule.class, m -> m.getId().equals(COMPOSTABLE_LIST)).getList();
        if (list.isEmpty())
        {
            complain();
            return getState();
        }

        if (InventoryUtils.hasItemInProvider(getOwnBuilding(), stack -> list.contains(new ItemStackHandling(stack))))
        {
            InventoryUtils.transferItemStackIntoNextFreeSlotFromProvider(
              getOwnBuilding(),
              InventoryUtils.findFirstSlotInProviderNotEmptyWith(getOwnBuilding(), stack -> list.contains(new ItemStackHandling(stack))),
              worker.getInventoryCitizen());
        }

        final int slot = InventoryUtils.findFirstSlotInItemHandlerWith(
          worker.getInventoryCitizen(),
          stack -> list.contains(new ItemStackHandling(stack))
        );
        if (slot >= 0)
        {
            worker.setItemInHand(Hand.MAIN_HAND, worker.getInventoryCitizen().getStackInSlot(slot));
            return START_WORKING;
        }

        worker.setItemInHand(Hand.MAIN_HAND, ItemStack.EMPTY);

        if (!getOwnBuilding().hasWorkerOpenRequests(worker.getCitizenData().getId()))
        {
            final ArrayList<ItemStack> itemList = new ArrayList<>();
            for (final ItemStorage item : list)
            {
                final ItemStack itemStack = item.getItemStack();
                itemStack.setCount(itemStack.getMaxStackSize());
                itemList.add(itemStack);
            }
            if (!itemList.isEmpty())
            {
                worker.getCitizenData().createRequestAsync(new StackList(itemList, COM_MINECOLONIES_REQUESTS_COMPOSTABLE, Constants.STACKSIZE * getOwnBuilding().getBarrels().size(), 1, getOwnBuilding().getSetting(BuildingComposter.MIN).getValue()));
            }
        }

        setDelay(2);
        return START_WORKING;
    }

    /**
     * Method for the AI to decide what to do. Possible actions: harvest barrels, fill barrels or idle
     *
     * @return the decision it made
     */
    private IAIState decideWhatToDo()
    {
        worker.getCitizenStatusHandler().setLatestStatus(new TranslationTextComponent(COM_MINECOLONIES_COREMOD_STATUS_IDLING));
        worker.getCitizenData().setVisibleStatus(VisibleCitizenStatus.WORKING);

        if (walkToBuilding())
        {
            setDelay(2);
            return getState();
        }

        final BuildingComposter building = this.getOwnBuilding();

        for (final BlockPos barrel : building.getBarrels())
        {
            final TileEntity te = world.getBlockEntity(barrel);
            if (te instanceof TileEntityBarrel)
            {

                this.currentTarget = barrel;
                if (((TileEntityBarrel) te).isDone())
                {
                    setDelay(DECIDE_DELAY);
                    worker.getCitizenData().setVisibleStatus(COMPOST);
                    return COMPOSTER_HARVEST;
                }
            }
        }

        for (final BlockPos barrel : building.getBarrels())
        {
            final TileEntity te = world.getBlockEntity(barrel);
            if (te instanceof TileEntityBarrel && !((TileEntityBarrel) te).checkIfWorking())
            {
                this.currentTarget = barrel;
                setDelay(DECIDE_DELAY);
                worker.getCitizenData().setVisibleStatus(COMPOST);
                return COMPOSTER_FILL;
            }
        }

        setDelay(DECIDE_DELAY);
        return START_WORKING;
    }

    /**
     * The AI will now fill the barrel that he found empty on his building
     *
     * @return the nex IAIState after doing this
     */
    private IAIState fillBarrels()
    {
        worker.getCitizenStatusHandler().setLatestStatus(new TranslationTextComponent(COM_MINECOLONIES_COREMOD_STATUS_COMPOSTER_FILLING));

        if (worker.getItemInHand(Hand.MAIN_HAND) == ItemStack.EMPTY)
        {
            final int slot = InventoryUtils.findFirstSlotInItemHandlerWith(
              worker.getInventoryCitizen(), stack -> getOwnBuilding().getModuleMatching(ItemListModule.class, m -> m.getId().equals(COMPOSTABLE_LIST)).isItemInList(new ItemStackHandling(stack)));

            if (slot >= 0)
            {
                worker.setItemInHand(Hand.MAIN_HAND, worker.getInventoryCitizen().getStackInSlot(slot));
            }
            else
            {
                return GET_MATERIALS;
            }
        }
        if (walkToBlock(currentTarget))
        {
            setDelay(2);
            return getState();
        }

        if (world.getBlockEntity(currentTarget) instanceof TileEntityBarrel)
        {

            final TileEntityBarrel barrel = (TileEntityBarrel) world.getBlockEntity(currentTarget);

            worker.getCitizenItemHandler().hitBlockWithToolInHand(currentTarget);
            barrel.addItem(worker.getItemInHand(Hand.MAIN_HAND));
            worker.getCitizenExperienceHandler().addExperience(BASE_XP_GAIN);
            this.incrementActionsDoneAndDecSaturation();
            worker.setItemInHand(Hand.MAIN_HAND, ItemStackUtils.EMPTY);

            incrementActionsDone();
        }
        setDelay(AFTER_TASK_DELAY);
        return START_WORKING;
    }

    /**
     * The AI will harvest the finished barrels they found in their hut.
     *
     * @return the next IAIState after doing this
     */
    private IAIState harvestBarrels()
    {
        worker.getCitizenStatusHandler().setLatestStatus(new TranslationTextComponent(COM_MINECOLONIES_COREMOD_STATUS_COMPOSTER_HARVESTING));

        if (walkToBlock(currentTarget))
        {
            setDelay(2);
            return getState();
        }

        if (world.getBlockEntity(currentTarget) instanceof TileEntityBarrel)
        {
            worker.getCitizenItemHandler().hitBlockWithToolInHand(currentTarget);

            final TileEntityBarrel te = (TileEntityBarrel) world.getBlockEntity(currentTarget);
            final ItemStack compost = te.retrieveCompost(getLootMultiplier(worker.getRandom()));

            if (getOwnBuilding().getSetting(BuildingComposter.PRODUCE_DIRT).getValue())
            {
                /**
                 * Podzol or dirt?
                 * 5% chance (by default) for podzol, else dirt.
                 * Two researches to increase it to 10% and 15%, respectively.
                 */
                if (((worker.getRandom().nextInt(100)) + 1) <= (5 * (1 + worker.getCitizenColonyHandler().getColony().getResearchManager().getResearchEffects().getEffectStrength(PODZOL_CHANCE))))
                {
                    InventoryUtils.addItemStackToItemHandler(worker.getInventoryCitizen(), new ItemStack(Blocks.PODZOL, MineColonies.getConfig().getServer().dirtFromCompost.get()));
                }
                else
                {
                    InventoryUtils.addItemStackToItemHandler(worker.getInventoryCitizen(), new ItemStack(Blocks.DIRT, MineColonies.getConfig().getServer().dirtFromCompost.get()));
                }
            }
            else
            {
                InventoryUtils.addItemStackToItemHandler(worker.getInventoryCitizen(), compost);
            }

            worker.getCitizenExperienceHandler().addExperience(BASE_XP_GAIN);
            this.incrementActionsDoneAndDecSaturation();
        }
        setDelay(AFTER_TASK_DELAY);
        return START_WORKING;
    }

    /**
     * Gives the loot multiplier based on the citizen level and a random number.
     *
     * @param random the random number to get the percentages
     * @return the multiplier for the amount of compost (base amount: 6)
     */
    private double getLootMultiplier(final Random random)
    {
        final int citizenLevel = (int) (getSecondarySkillLevel() / 2.0);
        final int diceResult = random.nextInt(100);

        if (diceResult <= citizenLevel * 2)
        {
            return DOUBLE;
        }
        if (diceResult <= citizenLevel * 4)
        {
            return 1.5;
        }
        if (diceResult <= citizenLevel * 8)
        {
            return 1.25;
        }

        return 1;
    }

    @Override
    protected int getActionsDoneUntilDumping()
    {
        return 1;
    }

    /**
     * If the list of allowed items is empty, the AI will message all the officers of the colony asking for them to set the list. Happens more or less once a day if the list is not
     * filled
     */
    private void complain()
    {
        if (ticksToComplain <= 0)
        {
            ticksToComplain = TICKS_UNTIL_COMPLAIN;
            for (final PlayerEntity player : getOwnBuilding().getColony().getMessagePlayerEntities())
            {
                player.sendMessage(new TranslationTextComponent(COM_MINECOLONIES_COREMOD_ENTITY_COMPOSTER_EMPTYLIST), player.getUUID());
            }
        }
        else
        {
            ticksToComplain--;
        }
    }

    @Override
    public Class<BuildingComposter> getExpectedBuildingClass()
    {
        return BuildingComposter.class;
    }
}
