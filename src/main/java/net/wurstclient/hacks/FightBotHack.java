/*
 * Copyright (C) 2014 - 2020 | Alexander01998 | All rights reserved.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathPos;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.commands.PathCmd;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.FakePlayerEntity;

@SearchTags({"fight bot"})
@DontSaveState
public final class FightBotHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Attack range (like Killaura)", 4.25, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting distance = new SliderSetting("Distance",
		"How closely to follow the target.\n"
			+ "This should be set to a lower value than Range.",
		3, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting useAi =
		new CheckboxSetting("Use AI (experimental)", false);
	
	private EntityPathFinder pathFinder;
	private PathProcessor processor;
	private int ticksProcessing;
	
	public FightBotHack()
	{
		super("FightBot",
			"A bot that automatically walks around and kills everything.\n"
				+ "Good for MobArena.");
		
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(distance);
		addSetting(useAi);
	}
	
	@Override
	public void onEnable()
	{
		// disable other killauras
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		// WURST.getHax().multiAuraHack.setEnabled(false);
		// WURST.getHax().protectHack.setEnabled(false);
		// WURST.getHax().tpAuraHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		
		pathFinder = new EntityPathFinder(MC.player);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	public void onDisable()
	{
		// remove listener
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		pathFinder = null;
		processor = null;
		ticksProcessing = 0;
		PathProcessor.releaseControls();
	}
	
	@Override
	public void onUpdate()
	{
		// set entity
		Stream<Entity> stream =
			StreamSupport.stream(MC.world.getEntities().spliterator(), true)
				.filter(e -> e instanceof LivingEntity)
				.filter(e -> !e.removed && ((LivingEntity)e).getHealth() > 0)
				.filter(e -> e != MC.player)
				.filter(e -> !(e instanceof FakePlayerEntity));
		
		Entity entity = stream
			.min(
				Comparator.comparingDouble(e -> MC.player.squaredDistanceTo(e)))
			.orElse(null);
		if(entity == null)
			return;
		
		if(useAi.isChecked())
		{
			// reset pathfinder
			if((processor == null || processor.isDone() || ticksProcessing >= 10
				|| !pathFinder.isPathStillValid(processor.getIndex()))
				&& (pathFinder.isDone() || pathFinder.isFailed()))
			{
				pathFinder = new EntityPathFinder(entity);
				processor = null;
				ticksProcessing = 0;
			}
			
			// find path
			if(!pathFinder.isDone() && !pathFinder.isFailed())
			{
				PathProcessor.lockControls();
				WURST.getRotationFaker()
					.faceVectorClient(entity.getBoundingBox().getCenter());
				pathFinder.think();
				pathFinder.formatPath();
				processor = pathFinder.getProcessor();
			}
			
			// process path
			if(!processor.isDone())
			{
				processor.process();
				ticksProcessing++;
			}
		}else
		{
			// jump if necessary
			if(MC.player.horizontalCollision && MC.player.onGround)
				MC.player.jump();
			
			// swim up if necessary
			if(MC.player.isTouchingWater() && MC.player.getY() < entity.getY())
				MC.player.addVelocity(0, 0.04, 0);
			
			// control height if flying
			if(!MC.player.onGround
				&& (MC.player.abilities.flying
					|| WURST.getHax().flightHack.isEnabled())
				&& MC.player.squaredDistanceTo(entity.getX(), MC.player.getY(),
					entity.getZ()) <= MC.player.squaredDistanceTo(
						MC.player.getX(), entity.getY(), MC.player.getZ()))
			{
				if(MC.player.getY() > entity.getY() + 1D)
					MC.options.keySneak.setPressed(true);
				else if(MC.player.getY() < entity.getY() - 1D)
					MC.options.keyJump.setPressed(true);
			}else
			{
				MC.options.keySneak.setPressed(false);
				MC.options.keyJump.setPressed(false);
			}
			
			// follow entity
			MC.options.keyForward.setPressed(
				MC.player.distanceTo(entity) > distance.getValueF());
			WURST.getRotationFaker()
				.faceVectorClient(entity.getBoundingBox().getCenter());
		}
		
		// check cooldown
		if(MC.player.getAttackCooldownProgress(0) < 1)
			return;
		
		// check range
		if(MC.player.squaredDistanceTo(entity) > Math.pow(range.getValue(), 2))
			return;
		
		// attack entity
		MC.interactionManager.attackEntity(MC.player, entity);
		MC.player.swingHand(Hand.MAIN_HAND);
	}
	
	@Override
	public void onRender(float partialTicks)
	{
		PathCmd pathCmd = WURST.getCmds().pathCmd;
		pathFinder.renderPath(pathCmd.isDebugMode(), pathCmd.isDepthTest());
	}
	
	private class EntityPathFinder extends PathFinder
	{
		private final Entity entity;
		
		public EntityPathFinder(Entity entity)
		{
			super(new BlockPos(entity));
			this.entity = entity;
			setThinkTime(1);
		}
		
		@Override
		protected boolean checkDone()
		{
			return done = entity.squaredDistanceTo(new Vec3d(current).add(0.5,
				0.5, 0.5)) <= Math.pow(distance.getValue(), 2);
		}
		
		@Override
		public ArrayList<PathPos> formatPath()
		{
			if(!done)
				failed = true;
			
			return super.formatPath();
		}
	}
}