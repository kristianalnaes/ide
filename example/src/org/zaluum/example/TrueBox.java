package org.zaluum.example;

import org.zaluum.runtime.Box;
import org.zaluum.runtime.Out;
import org.zaluum.runtime.RunnableBox;

@Box
public class TrueBox extends RunnableBox{
	@Out(x=16,y=8) public boolean out = true;
	public void contents(){}
}