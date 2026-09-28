package art.arcane.wormholes.portal.rtp;

public record RtpProbeBounds(
			int minimumX,
			int maximumX,
			int minimumY,
			int maximumY,
			int minimumZ,
			int maximumZ) {
	public static RtpProbeBounds of(
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		double centerX = destination.blockX() + 0.5D;
		double centerZ = destination.blockZ() + 0.5D;
		double anchorX = centerX - (envelope.minimumXOffset() + envelope.maximumXOffset()) / 2.0D;
		double anchorY = destination.feetY() - envelope.minimumYOffset();
		double anchorZ = centerZ - (envelope.minimumZOffset() + envelope.maximumZOffset()) / 2.0D;
		double minimumX = anchorX + envelope.minimumXOffset() - RtpSafetyValidator.HORIZONTAL_CLEARANCE_BLOCKS;
		double maximumX = anchorX + envelope.maximumXOffset() + RtpSafetyValidator.HORIZONTAL_CLEARANCE_BLOCKS;
		double maximumY = anchorY + envelope.maximumYOffset();
		double minimumZ = anchorZ + envelope.minimumZOffset() - RtpSafetyValidator.HORIZONTAL_CLEARANCE_BLOCKS;
		double maximumZ = anchorZ + envelope.maximumZOffset() + RtpSafetyValidator.HORIZONTAL_CLEARANCE_BLOCKS;
		return new RtpProbeBounds(
				floor(minimumX - RtpSafetyValidator.EPSILON),
				floor(maximumX + RtpSafetyValidator.EPSILON),
				destination.feetY() - 1,
				floor(maximumY + RtpSafetyValidator.EPSILON),
				floor(minimumZ - RtpSafetyValidator.EPSILON),
				floor(maximumZ + RtpSafetyValidator.EPSILON));
	}

	private static int floor(double value)
	{
		if(value < Integer.MIN_VALUE || value >= (double) Integer.MAX_VALUE + 1.0D)
		{
			throw new IllegalArgumentException("RTP snapshot coordinate exceeds integer bounds");
		}
		return (int) Math.floor(value);
	}

}
