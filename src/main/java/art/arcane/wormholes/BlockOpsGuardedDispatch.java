package art.arcane.wormholes;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import art.arcane.wormholes.service.WormholesTelemetry;

final class BlockOpsGuardedDispatch
{
	@FunctionalInterface
	interface RegionDispatch
	{
		boolean schedule(Runnable task);
	}

	private BlockOpsGuardedDispatch()
	{
	}

	static boolean dispatchGuarded(RegionDispatch dispatch, String bodyReason, String rejectedReason, Supplier<String> context, Runnable body)
	{
		return dispatchGuarded(dispatch, null, bodyReason, rejectedReason, context, body, null);
	}

	static boolean dispatchGuarded(RegionDispatch dispatch, BooleanSupplier ownsRegion, String bodyReason, String rejectedReason,
			Supplier<String> context, Runnable body)
	{
		return dispatchGuarded(dispatch, ownsRegion, bodyReason, rejectedReason, context, body, body);
	}

	static boolean dispatchGuarded(RegionDispatch dispatch, BooleanSupplier ownsRegion, String bodyReason, String rejectedReason,
			Supplier<String> context, Runnable body, Runnable localBody)
	{
		AtomicBoolean started = new AtomicBoolean(false);
		boolean scheduled;

		try
		{
			scheduled = dispatch.schedule(guard(started, bodyReason, context, body));
		}

		catch(Throwable error)
		{
			report(bodyReason, context, error);
			scheduled = false;
		}

		if(scheduled || started.get())
		{
			return true;
		}

		if(localBody == null || ownsRegion == null || !owns(ownsRegion, bodyReason, context))
		{
			WormholesTelemetry.countFailure(rejectedReason);
			return false;
		}

		guard(started, bodyReason, context, localBody).run();
		return true;
	}

	private static boolean owns(BooleanSupplier ownsRegion, String bodyReason, Supplier<String> context)
	{
		try
		{
			return ownsRegion.getAsBoolean();
		}

		catch(Throwable error)
		{
			report(bodyReason, context, error);
			return false;
		}
	}

	private static Runnable guard(AtomicBoolean started, String bodyReason, Supplier<String> context, Runnable body)
	{
		return () ->
		{
			if(!started.compareAndSet(false, true))
			{
				return;
			}

			try
			{
				body.run();
			}

			catch(Throwable error)
			{
				report(bodyReason, context, error);
			}
		};
	}

	private static void report(String bodyReason, Supplier<String> context, Throwable error)
	{
		WormholesTelemetry.countFailure(bodyReason);
		Wormholes.log().log(Level.WARNING, describe(context), error);
	}

	private static String describe(Supplier<String> context)
	{
		try
		{
			String described = context.get();
			return described == null ? "Portal rune work failed" : described;
		}

		catch(Throwable ignored)
		{
			return "Portal rune work failed";
		}
	}
}
