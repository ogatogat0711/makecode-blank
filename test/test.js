function line(){
	for(let index = 0; index < 5; index++){
		agent.move(UP, 1)
		agent.place(FORWARD)
	}
	agent.move(DOWN, 5)
}

let row = 2

player.onChat("go", function(){
	for(let index = 0; index < 3; index ++){
		line()
		agent.move(RIGHT, row)
		if (agent.inspect(AgentInspection.Block, FORWARD) == REDSTONE_BLOCK){
			row += 1
		}
	}
})