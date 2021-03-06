package micdoodle8.mods.galacticraft.core.energy.tile;

import mekanism.api.energy.EnergizedItemManager;
import mekanism.api.energy.IEnergizedItem;
import micdoodle8.mods.galacticraft.api.item.ElectricItemHelper;
import micdoodle8.mods.galacticraft.api.item.IItemElectric;
import micdoodle8.mods.galacticraft.api.transmission.grid.IElectricityNetwork;
import micdoodle8.mods.galacticraft.api.transmission.tile.IConductor;
import micdoodle8.mods.galacticraft.api.transmission.tile.IElectrical;
import micdoodle8.mods.galacticraft.api.vector.BlockVec3;
import micdoodle8.mods.galacticraft.core.energy.EnergyConfigHandler;
import micdoodle8.mods.galacticraft.core.energy.EnergyUtil;
import micdoodle8.mods.galacticraft.core.util.VersionUtil;
import micdoodle8.mods.miccore.Annotations.RuntimeInterface;
import micdoodle8.mods.miccore.Annotations.VersionSpecific;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.util.ForgeDirection;

import java.lang.reflect.Method;
import java.util.EnumSet;

public class TileBaseUniversalElectricalSource extends TileBaseUniversalElectrical
{
    /*
     * The main function to output energy each tick from a source.
     *
     * The source will attempt to produce into its outputDirections whatever energy
     * it has available, and will reduce its stored energy by the amount which is in fact used.
     *
     * Max output = this.storage.maxExtract.
     *
     * @return The amount of energy that was used.
     */
    public float produce()
    {
        return this.extractEnergyGC(null, this.produce(false), false);
    }

    /*
     * Function to produce energy each tick into the outputs of a source.
     * If simulate is true, no energy is in fact transferred.
     *
     * Note: even if simulate is false this does NOT reduce the source's own
     * energy storage by the amount produced, that needs to be done elsewhere
     * See this.produce() for an example.
     */
    public float produce(boolean simulate)
    {
        float amountProduced = 0;

        if (!this.worldObj.isRemote)
        {
            EnumSet<ForgeDirection> outputDirections = this.getElectricalOutputDirections();
            outputDirections.remove(ForgeDirection.UNKNOWN);

            BlockVec3 thisVec = new BlockVec3(this);
            for (ForgeDirection direction : outputDirections)
            {
                TileEntity tileAdj = thisVec.getTileEntityOnSide(this.worldObj, direction);

                if (tileAdj != null)
                {
                    float toSend = this.extractEnergyGC(null, Math.min(this.getEnergyStoredGC() - amountProduced, this.getEnergyStoredGC() / outputDirections.size()), true);
                    if (toSend <= 0)
                    {
                        continue;
                    }

                    if (tileAdj instanceof TileBaseConductor)
                    {
                        IElectricityNetwork network = ((IConductor) tileAdj).getNetwork();
                        if (network != null)
                        {
                            amountProduced += (toSend - network.produce(toSend, !simulate, this.tierGC, this));
                        }
                    }
                    else if (tileAdj instanceof TileBaseUniversalElectrical)
                    {
                  		amountProduced += ((TileBaseUniversalElectrical) tileAdj).receiveElectricity(direction.getOpposite(), toSend, this.tierGC, !simulate);
                    }
                    else
                    {
                        amountProduced += EnergyUtil.otherModsEnergyTransfer(tileAdj, direction.getOpposite(), toSend, simulate);
                    }
                }
            }
        }

        return amountProduced;
    }

    /**
     * Recharges electric item.
     */
    public void recharge(ItemStack itemStack)
    {
        if (itemStack != null)
        {
            Item item = itemStack.getItem();
            float maxExtractSave = this.storage.getMaxExtract();
            if (this.tierGC > 1)
            {
                this.storage.setMaxExtract(maxExtractSave * 2.5F);
            }
            float energyToCharge = this.storage.extractEnergyGC(this.storage.getMaxExtract(), true);

            if (item instanceof IItemElectric)
            {
                this.storage.extractEnergyGC(ElectricItemHelper.chargeItem(itemStack, energyToCharge), false);
            }
            else if (EnergyConfigHandler.isMekanismLoaded() && item instanceof IEnergizedItem && ((IEnergizedItem) item).canReceive(itemStack))
            {
                this.storage.extractEnergyGC((float) EnergizedItemManager.charge(itemStack, energyToCharge * EnergyConfigHandler.TO_MEKANISM_RATIO) * EnergyConfigHandler.MEKANISM_RATIO, false);
            }
            else if (EnergyConfigHandler.isIndustrialCraft2Loaded())
            {
                try
                {
                    Class<?> itemElectricIC2 = Class.forName("ic2.api.item.ISpecialElectricItem");
                    Class<?> itemElectricIC2B = Class.forName("ic2.api.item.IElectricItem");
                    Class<?> itemManagerIC2 = Class.forName("ic2.api.item.IElectricItemManager");
                    if (itemElectricIC2.isInstance(item))
                    {
                        //Implement by reflection:
                        //float energy = (float) ((ISpecialElectricItem)item).getManager(itemStack).charge(itemStack, energyToCharge * EnergyConfigHandler.TO_IC2_RATIO, 4, false, false) * EnergyConfigHandler.IC2_RATIO;
                        Object IC2item = itemElectricIC2.cast(item);
                        Method getMan = itemElectricIC2.getMethod("getManager", ItemStack.class);
                        Object IC2manager = getMan.invoke(IC2item, itemStack);
                        double result;
                        if (VersionUtil.mcVersionMatches("1.7.2"))
                        {
                            Method methodCharge = itemManagerIC2.getMethod("charge", ItemStack.class, int.class, int.class, boolean.class, boolean.class);
                            result = (Integer) methodCharge.invoke(IC2manager, itemStack, (int) (energyToCharge * EnergyConfigHandler.TO_IC2_RATIO), this.tierGC + 1, false, false);
                        }
                        else
                        {
                            Method methodCharge = itemManagerIC2.getMethod("charge", ItemStack.class, double.class, int.class, boolean.class, boolean.class);
                            result = (Double) methodCharge.invoke(IC2manager, itemStack, (double) (energyToCharge * EnergyConfigHandler.TO_IC2_RATIO), this.tierGC + 1, false, false);
                        }
                        float energy = (float) result * EnergyConfigHandler.IC2_RATIO;
                        this.storage.extractEnergyGC(energy, false);
                    }
                    else if (itemElectricIC2B.isInstance(item))
                    {
                        Class<?> electricItemIC2 = Class.forName("ic2.api.item.ElectricItem");
                        Object IC2manager = electricItemIC2.getField("manager").get(null);
                        double result;
                        if (VersionUtil.mcVersionMatches("1.7.2"))
                        {
                            Method methodCharge = itemManagerIC2.getMethod("charge", ItemStack.class, int.class, int.class, boolean.class, boolean.class);
                            result = (Integer) methodCharge.invoke(IC2manager, itemStack, (int) (energyToCharge * EnergyConfigHandler.TO_IC2_RATIO), this.tierGC + 1, false, false);
                        }
                        else
                        {
                            Method methodCharge = itemManagerIC2.getMethod("charge", ItemStack.class, double.class, int.class, boolean.class, boolean.class);
                            result = (Double) methodCharge.invoke(IC2manager, itemStack, (double) (energyToCharge * EnergyConfigHandler.TO_IC2_RATIO), this.tierGC + 1, false, false);
                        }
                        float energy = (float) result * EnergyConfigHandler.IC2_RATIO;
                        this.storage.extractEnergyGC(energy, false);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            //			else if (GCCoreCompatibilityManager.isTELoaded() && itemStack.getItem() instanceof IEnergyContainerItem)
            //			{
            //				int accepted = ((IEnergyContainerItem) itemStack.getItem()).receiveEnergy(itemStack, (int) Math.floor(this.getProvide(ForgeDirection.UNKNOWN) * EnergyConfigHandler.TO_TE_RATIO), false);
            //				this.provideElectricity(accepted * EnergyConfigHandler.TE_RATIO, true);
            //			}

            if (this.tierGC > 1)
            {
                this.storage.setMaxExtract(maxExtractSave);
            }
        }
    }

    @RuntimeInterface(clazz = "ic2.api.energy.tile.IEnergyEmitter", modID = "IC2")
    public boolean emitsEnergyTo(TileEntity receiver, ForgeDirection direction)
    {
        //Don't add connection to IC2 grid if it's a Galacticraft tile
        if (receiver instanceof IElectrical || receiver instanceof IConductor)
        {
            return false;
        }

        try
        {
            Class<?> energyTile = Class.forName("ic2.api.energy.tile.IEnergyTile");
            if (!energyTile.isInstance(receiver))
            {
                return false;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return this.getElectricalOutputDirections().contains(direction);
    }

    @RuntimeInterface(clazz = "ic2.api.energy.tile.IEnergySource", modID = "IC2")
    public double getOfferedEnergy()
    {
        return this.getProvide(ForgeDirection.UNKNOWN) * EnergyConfigHandler.TO_IC2_RATIO;
    }

    @RuntimeInterface(clazz = "ic2.api.energy.tile.IEnergySource", modID = "IC2")
    public void drawEnergy(double amount)
    {
        this.storage.extractEnergyGC((float) amount * EnergyConfigHandler.IC2_RATIO, false);
    }

    @VersionSpecific(version = "[1.7.10]")
    @RuntimeInterface(clazz = "ic2.api.energy.tile.IEnergySource", modID = "IC2")
    public int getSourceTier()
    {
        return this.tierGC + 1;
    }

    @RuntimeInterface(clazz = "mekanism.api.energy.ICableOutputter", modID = "Mekanism")
    public boolean canOutputTo(ForgeDirection side)
    {
        return this.getElectricalOutputDirections().contains(side);
    }

    //	@Override
    //	public ElectricityPack provideElectricity(ForgeDirection from, ElectricityPack request, boolean doProvide)
    //	{
    //		if (this.getElectricalOutputDirections().contains(from))
    //		{
    //			if (!doProvide)
    //			{
    //				return ElectricityPack.getFromWatts(this.getProvide(from), this.getVoltage());
    //			}
    //
    //			return this.provideElectricity(request, doProvide);
    //		}
    //
    //		return new ElectricityPack();
    //	}

    /**
     * A non-side specific version of provideElectricity for you to optionally
     * use it internally.
     */
    //	public ElectricityPack provideElectricity(ElectricityPack request, boolean doProvide)
    //	{
    //		if (request != null)
    //		{
    //			float requestedEnergy = Math.min(request.getWatts(), this.energyStored);
    //
    //			if (doProvide)
    //			{
    //				this.setEnergyStored(this.energyStored - requestedEnergy);
    //			}
    //
    //			return ElectricityPack.getFromWatts(requestedEnergy, this.getVoltage());
    //		}
    //
    //		return new ElectricityPack();
    //	}

    //	public ElectricityPack provideElectricity(float energy, boolean doProvide)
    //	{
    //		return this.provideElectricity(ElectricityPack.getFromWatts(energy, this.getVoltage()), doProvide);
    //	}

    //	public void produce()
    //	{
    //		if (!this.worldObj.isRemote)
    //		{
    //			for (ForgeDirection outputDirection : this.getElectricalOutputDirections())
    //			{
    //				if (outputDirection != ForgeDirection.UNKNOWN)
    //				{
    //					if (!this.produceUE(outputDirection))
    //					{
    //						this.produceExternal(outputDirection);
    //					}
    //				}
    //			}
    //		}
    //	}

    //	public boolean produceUE(ForgeDirection outputDirection)
    //	{
    //		if (!this.worldObj.isRemote && outputDirection != null && outputDirection != ForgeDirection.UNKNOWN)
    //		{
    //			float provide = this.getProvide(outputDirection);
    //
    //			if (provide > 0)
    //			{
    //				Vector3 thisVec = new Vector3(this);
    //				TileEntity outputTile = thisVec.modifyPositionFromSide(outputDirection).getTileEntity(this.worldObj);
    //				IElectricityNetwork outputNetwork = NetworkHelper.getElectricalNetworkFromTileEntity(outputTile, outputDirection);
    //
    //				if (outputNetwork != null)
    //				{
    //					ElectricityPack powerRequest = outputNetwork.getRequest(this);
    //
    //					if (powerRequest.getWatts() > 0)
    //					{
    //						ElectricityPack sendPack = ElectricityPack.min(ElectricityPack.getFromWatts(this.getEnergyStored(), this.getVoltage()), ElectricityPack.getFromWatts(provide, this.getVoltage()));
    //						float rejectedPower = outputNetwork.produce(sendPack, true, this);
    //						this.provideElectricity(Math.max(sendPack.getWatts() - rejectedPower, 0), true);
    //						return true;
    //					}
    //				}
    //				else if (outputTile instanceof IElectrical)
    //				{
    //					float requestedEnergy = ((IElectrical) outputTile).getRequest(outputDirection.getOpposite());
    //
    //					if (requestedEnergy > 0)
    //					{
    //						ElectricityPack sendPack = ElectricityPack.min(ElectricityPack.getFromWatts(this.getEnergyStored(), this.getVoltage()), ElectricityPack.getFromWatts(provide, this.getVoltage()));
    //						float acceptedEnergy = ((IElectrical) outputTile).receiveElectricity(outputDirection.getOpposite(), sendPack, true);
    //						this.provideElectricity(acceptedEnergy, true);
    //						return true;
    //					}
    //				}
    //			}
    //		}
    //
    //		return false;
    //	}

    //	public boolean produceExternal(ForgeDirection outputDirection)
    //	{
    //		if (!this.worldObj.isRemote && outputDirection != null && outputDirection != ForgeDirection.UNKNOWN)
    //		{
    //			float provide = this.getProvide(outputDirection);
    //
    //			if (this.getEnergyStored() >= provide && provide > 0)
    //			{
    //				TileEntity adjacentEntity = new Vector3(this).modifyPositionFromSide(outputDirection).getTileEntity(this.worldObj);
    //
    //				if (EnergyConfigHandler.isThermalExpansionLoaded())
    //				{
    //					if (adjacentEntity instanceof IEnergyHandler)
    //					{
    //						int teProvide = (int) Math.floor(provide * EnergyConfigHandler.TO_TE_RATIO);
    //						int energyUsed = Math.min(((IEnergyHandler) adjacentEntity).receiveEnergy(outputDirection.getOpposite(), teProvide, false), teProvide);
    //						this.provideElectricity(energyUsed * EnergyConfigHandler.TE_RATIO, true);
    //						return true;
    //					}
    //				}
    //
    ////				if (EnergyConfigHandler.isBuildcraftLoaded())
    ////				{
    ////					if (adjacentEntity instanceof IPowerReceptor)
    ////					{
    ////						PowerReceiver receiver = ((IPowerReceptor) adjacentEntity).getPowerReceiver(outputDirection.getOpposite());
    ////
    ////						if (receiver != null)
    ////						{
    ////							if (receiver.powerRequest() > 0)
    ////							{
    ////								float bc3Provide = provide * EnergyConfigHandler.TO_BC_RATIO;
    ////								float energyUsed = Math.min(receiver.receiveEnergy(Type.MACHINE, bc3Provide, outputDirection.getOpposite()), bc3Provide);
    ////								this.provideElectricity(energyUsed * EnergyConfigHandler.BC3_RATIO, true);
    ////							}
    ////						}
    ////
    ////						return true;
    ////					}
    ////				}
    //			}
    //		}
    //
    //		return false;
    //	}
    @Override
    public float getProvide(ForgeDirection direction)
    {
        if (direction == ForgeDirection.UNKNOWN && EnergyConfigHandler.isIndustrialCraft2Loaded())
        {
            TileEntity tile = new BlockVec3(this).getTileEntityOnSide(this.worldObj, this.getElectricalOutputDirectionMain());
            if (tile instanceof IConductor)
            {
                //No power provide to IC2 mod if it's a Galacticraft wire on the output.  Galacticraft network will provide the power.
                return 0.0F;
            }
        }

        if (this.getElectricalOutputDirections().contains(direction))
        {
            return this.storage.extractEnergyGC(Float.MAX_VALUE, true);
        }

        return 0F;
    }

    public ForgeDirection getElectricalOutputDirectionMain()
    {
        return ForgeDirection.UNKNOWN;
    }

    @RuntimeInterface(clazz = "buildcraft.api.power.IPowerEmitter", modID = "")
    public boolean canEmitPowerFrom(ForgeDirection side)
    {
        return this.getElectricalOutputDirections().contains(side);
    }
    
    @Override
    @RuntimeInterface(clazz = "cofh.api.energy.IEnergyHandler", modID = "")
    public int extractEnergy(ForgeDirection from, int maxExtract, boolean simulate)
    {
    	if (!this.getElectricalOutputDirections().contains(from))
    	{
    		return 0;
    	}

    	return MathHelper.floor_float(this.storage.extractEnergyGC(maxExtract * EnergyConfigHandler.RF_RATIO, !simulate) * EnergyConfigHandler.TO_RF_RATIO);
    }
}
