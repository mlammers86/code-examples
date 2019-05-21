var chartfuncs = function(){
	function hotterDates(dtms) {
		/*
		Takes the dattims (in YYYYMMDDHHmm form) and outputs an identical array of date/times as epoch seconds.
		*/
		var newdtms = []
		for (var j=0;j<dtms.length;j++) {
			newdtms.push(moment(dtms[j]+'',"YYYYMMDDHHmm").valueOf())
		}
		return newdtms
	}
    
	function getMax(dt) {
		/*
		Basic helper function to get the maximum value from an array and then return
		the proper value. Basically, these charts shouldn't have a max less than 0, and
		if they do have a max, the actual chart value should be one above to pad.
		*/
		var thismax = d3.max(dt, function(d) {return d})
		if (thismax>0) {
			return thismax+1
		} else {
			return 0
		}
	}

	function getMin(dt,typ) {
		/*
		Similar to getMax, it isn't just giving you your minimum value, it's returning
		a value relevant to the chart tool. So generally min will be 0, unless it's less
		than 0.
		*/
		//console.log(typ)
		var thismax = d3.min(dt, function(d) {return d})
		if (thismax<0) {
			return thismax
		} else if (typ === 'BARO') {
			return thismax-1
		} else {
			return 0
		}
	}
    
	function getData(cg,callback) {
		/*
		When the "Get Data" button is clicked (or when the page is initially loaded), this function is called.
		The "callback" input is a function that gets called once the ajax request has completed (need it to be synchronous).
		*/
		//Gets either the date range or the recent range and then constructs the URL for the API call
		var recval = $('input[name="recent"]').val()
		var stidval = cg.stid
		var begval = $('input[name="start"]').val()
		var endval = $('input[name="end"]').val()
		var thisurl = "./data/wx/series.php?site="+stidval+"&begin="+begval+"000000&end="+endval+"235959"
		if (recval.length>0) { //Recent takes precedence
			thisurl = "./data/wx/series.php?site="+stidval+"&recent="+recval
			history.pushState({},'','#'+cg.thispage+'/'+stidval+'/'+recval)
		} else {
			history.pushState({},'','#'+cg.thispage+'/'+stidval+'/'+begval+'/'+endval)
		}
		
		$.ajax({
			//Note that even though I'm asking for start date and end date, the API actually wants hhmm as well...but we aren't worried about that.
			url: thisurl,
			dataType: "json",
			success: function(d) {
	  		//A lot of this is resetting globals and clearing the existing charts
				cg.vars = []
				cg.xscl = null
				cg.dattim = null
				cg.convdattim = null

				//By removing the chartwrappers, we remove everything they contain
                for (var cw=0;cw<cg.chartwrappers.length;cw++) {
                    cg.chartwrappers[cw].remove()
                }

				cg.chartwrappers = []
				cg.charts = []
				cg.chartdatas = []

				//MTMonitor doesn't need all these things, so it only gets handled when willFlag is true
				if (cg.willFlag) {
					cg.selecting = false
					cg.startsel = 0
					cg.endsel = 0
					cg.startind = 0
					cg.endind = 0
					//Have to remove the table from the window before nulling it
					if (cg.tbl !== null) {
						cg.tbl.remove()
					}
					cg.tbl = null
				}

				//Get rid of a couple more elements
				d3.select("#removebtn").remove()
				d3.select(cg.sbid+" table").remove()

				cg.dattim  = d.observations.dattim
				cg.convdattim = hotterDates(cg.dattim)
				if (recval.length>0) {
					begval = cg.dattim[0]
					endval = cg.dattim[cg.dattim.length-1]
				} else {
					begval = begval+'000000'
					endval = endval+'235959'
				}

				$.get({
					url: "./data/qc/?site="+stidval+"&begin="+begval+"&end="+endval,
					dataType: "json",
					success: function(k) {
						/*
						So the list of QC objects we get back from the QC API is pretty useless, so we create
						this cg.ogqc object (because this is the OG QC for the site) that is more easily searchable.
						We also create a cg.newqc object that will be modified by user actions which will be diffed.
						*/
						cg.ogqc = {}
						cg.newqc = {}
						var q = k.flags
						if (q) {
							for (var z=0;z<q.length;z++) {
								if (cg.ogqc[q[z].channel]) {
									if (cg.ogqc[q[z].channel][q[z].flag]) {
										cg.ogqc[q[z].channel][q[z].flag].push(parseInt(q[z].dattim))
										cg.newqc[q[z].channel][q[z].flag].push(parseInt(q[z].dattim))
									} else {
										cg.ogqc[q[z].channel][q[z].flag] = [parseInt(q[z].dattim)]
										cg.newqc[q[z].channel][q[z].flag] = [parseInt(q[z].dattim)]
									}
								} else {
									cg.ogqc[q[z].channel] = {}
									cg.ogqc[q[z].channel][q[z].flag] = [parseInt(q[z].dattim)]
									cg.newqc[q[z].channel] = {}
									cg.newqc[q[z].channel][q[z].flag] = [parseInt(q[z].dattim)]
								}
							}
						}
						init(d,cg)
						if (callback) {
							callback()
						}
					},
					error: function(e) {
						//The user should know that if the QC API request fails, they're sorta winging it on adding entries.
						//alert('QC Query Error') Okay, we aren't doing this yet because it ALWAYS fails
						cg.ogqc = {}
						cg.newqc = {}
						init(d,cg)
						if (callback) {
								callback()
						}
					}
				})
	  	},
	  	error: function(d) {
	  		alert("API Request Failed")
	  	}
	  })
	}
    
	function addChart(n,cg) {
		/*
		So, this is called "addChart", but it only adds the container for a chart, not the lines and data.
		Now, a chart has a few elements, including the mouseover line, the selection rectangle. It also adds
		the column of checkboxes for the chart in the variable selection table.
		It takes an integer, "n", that is the current chart index value, and the global chart object.
		*/
		cg.chartwrappers.push(d3.select(cg.wrapid).insert("div","#buttondiv")
			.attr('id','wrapper'+n)
			.style("height",(cg.height+50)+'px')
			.style("width",(cg.width+125)+'px')
			.style("border","1px solid black"))
		cg.charts.push(cg.chartwrappers[cg.chartwrappers.length-1].append('svg')
			.attr("class","chrt")
			.attr("id","graph"+n)
			.attr("height",cg.height+50)
			.attr("width",cg.width+125)
			.style("display","none")
			.style("padding-top","25px"))
		cg.chartdatas.push({'var1':{'currentVars':[],'yScale':null,'unit':null},'var2':{'currentVars':[],'yScale':null,'unit':null}})

		cg.charts[cg.charts.length-1].append("line") //Create the (currently invisible) mouseover line
			.attr("class","vline")
			.attr("y1",0)
			.attr("y2",cg.height)
			.attr("x1",50)
			.attr("x2",50)
			.attr("stroke-width",1)
			.attr("stroke","black")
			.style("opacity",0)

		cg.charts[cg.charts.length-1].append("clipPath")
			.attr('id','cp-'+(cg.charts.length-1))
			.append("rect")
			.attr("y",0)
			.attr("x",50)
			.attr("width",cg.width)
			.attr("height",cg.height+50)	

		cg.charts[cg.charts.length-1].append("rect") //Create the (currently invisible) selector line
			.attr("class","srect")
			.attr("height",cg.height)
			.attr("y",0)
			.attr("x",0)
			.attr("width",0)
			.attr("stroke-width",0)
			.attr("fill","grey")
			.attr('clip-path','url(#cp-'+(cg.charts.length-1)+')')
			.style("opacity",0)	

		// Add the new chart to the variable checkbox table
		d3.select('#checkhead').append("td")
			.attr("class","ckcoltd-"+n)
			.text("Chart "+(n+1))

		d3.selectAll('.ckrow')
			.append("td")
			.attr('class',"ckcoltd-"+n)
			.html(function(d,v) {return "<input id='ck"+n+"-"+v+"'' class='varcheck ckcol-"+n+"' type='checkbox' title='Check to Add to Chart "+(n+1)+"'>"})
		d3.selectAll(".ckcol-"+n).on('click',function() {
			varCheckOnClick(this,cg)
		})
	}
    
	function init(dt,cg) {
		/*
		Called once data have been acquired, init takes "dt", which is the data
		from the series API return, as well as the global chart object.
		It processes this data JSON and puts it into the vars object in the global
		object. Then it adds the first chart object and creates the table element.
		*/
		var colors = [{'max':'#273a37','avg':'#5a877f','std':'#8dd3c7'},
			{'max':'#34333c','avg':'#79778b','std':'#bebada'},
			{'max':'#452320','avg':'#a05249','std':'#fb8072'},
			{'max':'#23313a','avg':'#527187','std':'#80b1d3'},
			{'max':'#45321b','avg':'#a2733f','std':'#fdb462'},
			{'max':'#313d1d','avg':'#728e43','std':'#b3de69'},
			{'max':'#45383f','avg':'#a18392','std':'#fccde5'},
			{'max':'#3c3c3c','avg':'#8b8b8b','std':'#d9d9d9'},
			{'max':'#342334','avg':'#785279','std':'#bc80bd'},
			{'max':'#384136','avg':'#82967e','std':'#ccebc5'},
			{'max':'#46411f','avg':'#a39747','std':'#ffed6f'},
			{'max':'#464631','avg':'#a3a372','std':'#ffffb3'},
			{'max':'#417c6a','avg':'#66c2a5','std':'#9dd8c5'},
			{'max':'#a15a3f','avg':'#fc8d62','std':'#fdb69b'},
			{'max':'#5a6682','avg':'#8da0cb','std':'#b6c2dd'},
			{'max':'#93587d','avg':'#e78ac3','std':'#efb4d8'},
			{'max':'#6a8a36','avg':'#a6d854','std':'#c6e692'},
			{'max':'#a38b1e','avg':'#ffd92f','std':'#ffe67a'},
			{'max':'#927d5f','avg':'#e5c494','std':'#eed9ba'}]

		if (!dt.metadata) {
			alert('Invalid Station ID')
			return false
		}
		cg.sitesite = dt.metadata.site
		cg.stname = dt.metadata.station_name
		cg.projid = dt.metadata.project_id
		d3.select('#back').attr('href','#projects/'+cg.projid)
		d3.select('#siteid').text(cg.sitesite)
	    d3.select('#sitename').text(cg.stname)
		var dkeys = Object.keys(dt.metadata.variables)
		dkeys.sort(function(a,b) { //This logic orders the instruments by priority: var type, then height.
			var as = a.split('-')
			var bs = b.split('-')
			var avind
			var bvind
			if (as[0] === 'temp') {
				avind = 2
			} else if (as[0] === 'wdir') {
				avind = 1
			} else if (as[0] === 'anem') {
				avind = 0
			} else {
				avind = 3
			}
			if (bs[0] === 'temp') {
				bvind = 2
			} else if (bs[0] === 'wdir') {
				bvind = 1
			} else if (bs[0] === 'anem') {
				bvind = 0
			} else {
				bvind = 3
			}
			if (avind !== bvind) {
				return avind-bvind
			} else {
				if (as[1]>bs[1]) {
					return -1
				} else {
					return 1
				}
			}
		})
		var coloriter = 0
		//Loop through the channels (dkeys) and get the max, avg, and std values, putting them into arrays.
		for (var inum=0;inum<dkeys.length;inum++) {
			//We can currently only handle 18 colors...need to fix this maybe.
			if (coloriter>18) {
				break
			}
			var thiskey = dkeys[inum]
			//Lots of "if" handling because there can be null/zero-length things returned from the API which will break the code.
			if (dt.observations[thiskey] != null) {
				if (dt.observations[thiskey]['data-ave'].length>0) {
					if (cg.channels == undefined) {
						cg.channels = {}
					}
					var tdat = dt.metadata.variables[thiskey]
					if (tdat.height !== null && notRemoved(tdat.removed_date,dt.observations.dattim[0])) { //Every instrument must have a height...height is so crucial to these data.
						var transdat = dt.observations[thiskey]
						cg.channels[tdat.chl] = []
						if (cg.willFlag) {
							cg.qc[tdat.chl] = transdat.qc
						}
						var minMin = getMin(transdat["data-max"],tdat.typ)
						var maxMax = getMax(transdat["data-max"])
						var thisrow = null
						if (minMin !== maxMax) { //This is basically checking if it's a straight line.
							cg.channels[tdat.chl].push(cg.vars.length)
							cg.vars.push({'channel':tdat.chl,'typ':'max','height':tdat.height,'name':tdat.typ+' '+tdat.height+'m Max','id':thiskey,'unit':tdat.unit,'data':transdat['data-max'],'max':maxMax,'min':minMin,'color':colors[coloriter].max})
							//Have to add a row for each variable to the variable checkbox table.
							thisrow = d3.select('#checktable').append("tr")
								.attr('id','ctrow-'+(cg.vars.length-1))
								.attr("class","ckrow")
							thisrow.append("td")
								.attr('class','ckcolor')
								.style("background-color",colors[coloriter].max)
							thisrow.append("td")
								.text(tdat.typ+' '+tdat.height+'m Max')
						}
						minMin = getMin(transdat["data-ave"],tdat.typ)
						maxMax = getMax(transdat["data-ave"])
						if (minMin !== maxMax) {
							cg.channels[tdat.chl].push(cg.vars.length)
							cg.vars.push({'channel':tdat.chl,'typ':'avg','height':tdat.height,'name':tdat.typ+' '+tdat.height+'m Avg','id':thiskey,'unit':tdat.unit,'data':transdat["data-ave"],'max':maxMax,'min':minMin,'color':colors[coloriter].avg})
							thisrow = d3.select('#checktable').append("tr")
								.attr('id','ctrow-'+(cg.vars.length-1))
								.attr("class","ckrow")
							thisrow.append("td")
								.attr('class','ckcolor')
								.style("background-color",colors[coloriter].avg)
							thisrow.append("td")
								.text(tdat.typ+' '+tdat.height+'m Avg')
						}
						minMin = getMin(transdat["data-stddev"],tdat.typ)
						maxMax = getMax(transdat["data-stddev"])
						if (minMin !== maxMax) {
							cg.channels[tdat.chl].push(cg.vars.length)
							cg.vars.push({'channel':tdat.chl,'typ':'std','height':tdat.height,'name':tdat.typ+' '+tdat.height+'m Std','id':thiskey,'unit':tdat.unit,'data':transdat["data-stddev"],'max':maxMax,'min':minMin,'color':colors[coloriter].std})
							thisrow = d3.select('#checktable').append("tr")
								.attr('id','ctrow-'+(cg.vars.length-1))
								.attr("class","ckrow")
							thisrow.append("td")
								.attr('class','ckcolor')
								.style("background-color",colors[coloriter].std)
							thisrow.append("td")
								.text(tdat.typ+' '+tdat.height+'m Std')
						}

						coloriter++
					}
				}
			}
		}

		//console.log(cg.vars)
		if (cg.vars.length<1) {
			alert("No data for this station.")
			return false
		}

		//Create the initial chart
		addChart(0,cg)

		cg.dattim = dt.observations.dattim
		cg.convdattim = hotterDates(cg.dattim)
		if (cg.willFlag) {
			//Create the empty table at the bottom of the page
		  cg.tbl = d3.select(cg.tblrowid).append("table")
		  	.attr("id",cg.tblid.replace("#",''))
		  	.style("width",(cg.width+150)+'px')
		  	.attr("class","col-12")
		}

		//All x scales are the same initially (it will be modified by zooming later)...if they aren't, it's a problem.
		cg.xscl = d3.scaleTime()
			.domain([cg.convdattim[0],cg.convdattim[cg.convdattim.length-1]])
			.range([50,cg.width+50])

		//Establishes the onclick functionality for the variable table color changing
		d3.selectAll('.ckcolor')
			.style('cursor','pointer')
			.attr('title','Click to Change Color')
			.on('click',function() {
				var vid = d3.select(this.parentNode).attr('id').split('-')[1]
				colorChooser(cg,vid)
			})

		if (cg.willFlag) {
			d3.select('#ck0-0').property('checked',true)
			varCheckOnClick('#ck0-0',cg)
		}
	}
    
	function makeGraph(graphnum,num,cg) {
		/*
		This function currently generates a line graph showing minimum, maximum, and average values for any variable.
		It takes the arguments "graphnum" (the index of the chart), "num" (the index of the variable being plotted), and "cg" (the chart global)
		*/
		var graph = cg.charts[graphnum]
		var gdat = cg.chartdatas[graphnum]
		var thisvar = cg.vars[num]

		var thismin = 9999
		var thismax = 0
		var getLine = null
		var v1 = true

		/*
		So here's the way this works. If the chart doesn't have anything, it gets handled in the first block.
		If the chart has data already and the new variable has the same units as the first axis, it uses that axis. That's in the second block.
		If the chart has data already and the new variable has different units, it creates a second axis. That's in the third block.
		If the chart has two axes already and the new variable has the same units as the second axis, that's in the third block.
		If the chart has two axes already and the new variable has different units, the function returns false and alerts the user.
		*/
		if (gdat.var1.currentVars.length<1) {
			graph.style("display","block")
			gdat.var1.currentVars.push(num)
			gdat.var1.unit = thisvar.unit
			//if (thisvar.min<0) {
				thismin = thisvar.min
			//}
			thismax = thisvar.max
			gdat.var1.yScale = d3.scaleLinear()
				.domain([thismin,thismax])
				.range([cg.height,0])

			//Create a general accessor function that modifies line function based on which variable (x,a,n)
			getLine = d3.line()
				.defined(function(d,i) {
					if (i<cg.convdattim.length-1) {
						return (cg.convdattim[i+1]-cg.convdattim[i]) === (cg.convdattim[1]-cg.convdattim[0])
					} else if (d === null) {
						return false
					} else {
						return true
					}
				})
				.x(function(d,i) { return cg.xscl(cg.convdattim[i])})
				.y(function(d) {return gdat.var1.yScale(d*thisvar.scale+thisvar.offset)})
				.curve(d3.curveMonotoneX)

			if (cg.endsel>0) {
				//If another graph already has a visible rectangle, this graph needs to have a visible rectangle.
				if (cg.endsel>cg.startsel) {
	    		d3.selectAll('.srect').attr("width",cg.endsel-cg.startsel)
	    			.attr("x",cg.startsel)
	    			.style("opacity",1)
	    	} else {
			    d3.selectAll('.srect').attr("width",cg.startsel-cg.endsel)
			    	.attr("x",cg.endsel)
			    	.style("opacity",1)
	    	}
			}
		} else {
			var tvar = null
			if (thisvar.unit == gdat.var1.unit) {
				gdat.var1.currentVars.push(num)
				for (var v=0;v<gdat.var1.currentVars.length;v++) {
					//Have to loop through variables to get max/min
					tvar = cg.vars[gdat.var1.currentVars[v]]
					if (tvar.min<thismin) {
						thismin = tvar.min
					}
					if (tvar.max>thismax) {
						thismax = tvar.max
					}
				}
				gdat.var1.yScale = d3.scaleLinear()
					.domain([thismin,thismax])
					.range([cg.height,0])

				getLine = d3.line()
					.defined(function(d,i) {
						if (i<cg.convdattim.length-1) {
							return (cg.convdattim[i+1]-cg.convdattim[i]) === (cg.convdattim[1]-cg.convdattim[0])
						} else if (d === null) {
							return false
						} else {
							return true
						}
					})
					.x(function(d,i) { return cg.xscl(cg.convdattim[i])})
					.y(function(d) {return gdat.var1.yScale(d*thisvar.scale+thisvar.offset)})
					.curve(d3.curveMonotoneX)
			} else {
				v1 = false
				if (gdat.var2.currentVars.length<1) {
					gdat.var2.currentVars.push(num)
					gdat.var2.unit = thisvar.unit
					//if (thisvar.min<0) {
						thismin = thisvar.min
					//}
					thismax = thisvar.max
					gdat.var2.yScale = d3.scaleLinear()
						.domain([thismin,thismax])
						.range([cg.height,0])

					getLine = d3.line()
						.defined(function(d,i) {
							if (i<cg.convdattim.length-1) {
								return (cg.convdattim[i+1]-cg.convdattim[i]) === (cg.convdattim[1]-cg.convdattim[0])
							} else if (d === null) {
								return false
							} else {
								return true
							}
						})
						.x(function(d,i) { return cg.xscl(cg.convdattim[i])})
						.y(function(d) {return gdat.var2.yScale(d*thisvar.scale+thisvar.offset)})
						.curve(d3.curveMonotoneX)
				} else if (thisvar.unit == gdat.var2.unit) {
					gdat.var2.currentVars.push(num)
					for (var i=0;i<gdat.var2.currentVars.length;i++) {
						tvar = cg.vars[gdat.var2.currentVars[i]]
						if (tvar.min<thismin) {
							thismin = tvar.min
						}
						if (tvar.max>thismax) {
							thismax = tvar.max
						}
					}
					gdat.var2.yScale = d3.scaleLinear()
						.domain([thismin,thismax])
						.range([cg.height,0])

					getLine = d3.line()
						.defined(function(d,i) {
							if (i<cg.convdattim.length-1) {
								return (cg.convdattim[i+1]-cg.convdattim[i]) === (cg.convdattim[1]-cg.convdattim[0])
							} else if (d === null) {
								return false
							} else {
								return true
							}
						})
						.x(function(d,i) { return cg.xscl(cg.convdattim[i])})
						.y(function(d) {return gdat.var2.yScale(d)})
						.curve(d3.curveMonotoneX)
				} else {
					alert('Maximum 2 Different Variables per Graph')
					d3.select('#ck'+graphnum+'-'+num).property('checked',false)
					return false
				}
			}
		}

		//Have to recreate all of the axes and gridlines because their ranges may have changed
		graph.select("g.gridY").remove()
		graph.select(".xaxis").remove()
		graph.selectAll(".yaxis").remove()
		graph.selectAll(".ytitle").remove()

		// add the X gridlines
	  graph.append("g")			
	    .attr("class", "grid gridX")
	    .attr("transform", "translate(0," + cg.height + ")")
	    .call(d3.axisBottom(cg.xscl).tickValues(tickGen(cg,1)).tickSize(-cg.height).tickFormat(""))
	  /*
	  What's going on in this "call" function - so, we're creating an axisBottom based on the current xscl domain and range (based on zoom).
	  Then we generate tickValues, but when you initially add a line to the chart, we want to add ticks based on how long the data are (thus, tickGen).
		This section is just for the thin X gridlines.
	  */

		// gridlines in y axis function
		function make_y_gridlines() {		
	    return d3.axisLeft(gdat.var1.yScale)
	        .ticks(5)
		}

	  // add the Y gridlines
	  graph.append("g")			
	    .attr("class", "grid gridY")
	    .attr("transform", "translate(50,0)")
	    .call(make_y_gridlines()
	        .tickSize(-cg.width)
	        .tickFormat("")
	    )

	  graph.select('.gridzero').remove() //Have to replace those gridzero when we add another thing to the chart...
	  if (thismin<0) {
	  	if (v1) {
			  //Add a 0 gridline if y-axis goes below zero
			  graph.append("g")
			  	.attr("class","grid gridY gridzero")
			  	.attr("transform", "translate(50,0)")
			  	.call(d3.axisLeft(gdat.var1.yScale).tickValues([0]).tickSize(-cg.width).tickFormat(''))
			} else {
				graph.append("g")
			  	.attr("class","grid gridY gridzero")
			  	.attr("transform", "translate(50,0)")
			  	.call(d3.axisLeft(gdat.var2.yScale).tickValues([0]).tickSize(-cg.width).tickFormat(''))
			}
		}

		d3.selectAll('.gridzero line').style('stroke','black').style('stroke-opacity','1').style('stroke-width','2px')

	  graph.append('path')
			.datum(thisvar.data)
			.attr('class','datpath')
			.attr('id','c'+graphnum+'l'+num)
			.attr("stroke-width",1)
			.attr("stroke",thisvar.color)
			.attr("fill","none")
			.attr('d',getLine)

		// Updates the data paths on the graph to reflect possible new max/mins
	  refreshLines(graphnum,gdat,cg)

		//Append axes last so they sit on top of data
		graph.append("g")
			.attr("class","xaxis")
	    .attr('clip-path','url(#cp-'+graphnum+')')
			.attr("transform", "translate(0,"+cg.height+")")
			.call(d3.axisBottom(cg.xscl).tickValues(tickGen(cg,1)).tickFormat(d3.timeFormat("%Y/%m/%d %H:%M")))

		graph.append("g")
			.attr("class","yaxis")
			.attr("transform", "translate(50,0)")
			.call(d3.axisLeft(gdat.var1.yScale).ticks(5))

		graph.append("text")
			.attr("class","ytitle")
			.attr('transform','rotate(-90)')
			.attr('y',0)
			.attr('x',-cg.height/2)
			.attr('dy','1em')
			.attr('text-anchor','middle')
			.text(gdat.var1.unit)

		//Create second axis if there are vars in var2 and label it.
		if (gdat.var2.currentVars.length>0) {
			graph.append("g")
				.attr("class","yaxis")
				.attr("transform", "translate("+(cg.width+50)+")")
				.call(d3.axisRight(gdat.var2.yScale).ticks(5))

			graph.append("text")
				.attr("class","ytitle")
				.attr('transform','rotate(-90)')
				.attr('y',cg.width+90)
				.attr('x',-cg.height/2)
				.attr('dy','1em')
				.attr('text-anchor','middle')
				.text(gdat.var2.unit)
		}

		//Replace the default x-axis ticks with rotated date/time strings from the API
		/*graph.selectAll(".xaxis .tick text")
			.text(function(d) {
				var currtxt = d3.select(this).text()
				return hotDate(cg.dattim[parseInt(currtxt.replace(',',''))]).substr(0,16)
			})*/

		//Handle mouseover (tooltip/extend selection), mouseleave (hide tooltip, end selection), and click (start/stop selection)
		graph.on("mousemove", function(d) {
			mouseOver(d3.mouse(this),gdat,cg)
		})       					
	    .on("mouseleave", function(d) {
	    	mouseOut(cg)
	  	})
	  	.on("click", function(d) {
	  		onClick(d3.mouse(this),cg)
	  		//The below chunk is for when we did drag select for rectangles...because of zoom, we can't do that.
	  		/*d3.select(this).on("mouseup",function() {
	  			onClick(d3.mouse(this),cg)
	  		})*/
	  	})

	  if (!gdat.canZoom) {
	  	addZoom(cg,graphnum)
	  }

	  //Regardless, automatic and saved QC should display on the chart
	  //console.log(thisvar.id)
	  if (thisvar.id !== null && cg.willFlag) {
  		//Add autoqc
  		autoQC(num,thisvar,cg)
  		manQC(num,thisvar,cg)
	  }
	  
	  //Update the table if it exists
	  if (cg.willFlag) {
	    if (cg.endsel>0) {
		  	buildATable(cg)
		  }
	  }
	}
    
    /*
	Anything used in the HTML JS code needs to be included here to be available to the chartfuncs namespace.
	*/
	return {
		getData: getData,
		init: init,
		addChart: addChart
	}
}()