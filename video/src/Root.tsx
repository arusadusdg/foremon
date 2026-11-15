import React from "react";
import { Composition } from "remotion";
import { Video, totalFrames } from "./Video";

const FPS = 30;

export const Root: React.FC = () => (
  <Composition id="ForemonTikTok" component={Video} durationInFrames={totalFrames(FPS)} fps={FPS} width={1080} height={1920} />
);
