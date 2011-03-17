package aQute.speak.felix.talker;

import java.io.PrintStream;

import org.apache.felix.shell.Command;

import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Reference;
import aQute.service.speak.Speak;

@Component
public class Talker implements Command {

	private Speak speak;
	@Reference
	protected void setSpeak( Speak speak ) throws Exception {
		this.speak = speak;
		// speak.say("Felix talker registered"); // becomes annoying
	}
	
	@Override
	public void execute(String arg0, PrintStream arg1, PrintStream arg2) {
		try {
			speak.say(arg0.substring(5));
		} catch (Exception e) {
			e.printStackTrace(arg2);
		}
	}
	@Override
	public String getName() {
		return "talk";
	}
	@Override
	public String getShortDescription() {
		return "Talks too much";
	}
	@Override
	public String getUsage() {
		return "talk text ...";
	}
}
