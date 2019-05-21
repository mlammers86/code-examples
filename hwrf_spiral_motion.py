import pygrib,json,sys,h5py,os,copy
import numpy as np
import numpy.ma as ma
import matplotlib.pyplot as plt

def truncate(flt,acc):
	"""
	This function takes a float, turns it into a string, splits on the decimal and returns a string that
	is truncated to the number of decimal places specified in acc.
	"""
	flt = str(flt)
	flsplit = flt.split('.')
	if len(flsplit)>1:
		flcomb = flsplit[0]+'.'+flsplit[1][:acc]
		return flcomb
	else:
		return flt


#track = [[-78.9,26.6],[-79.0,26.8],[-79.1,27.0],[-79.2,27.1],[-79.4,27.3],[-79.6,27.5],[-79.7,27.6],[-79.8,27.8],[-79.9,28],[-80,28.2],[-80.1,28.4],[-80.1,28.6],[-80.3,28.9],[-80.3,29],[-80.5,29.1],[-80.5,29.4],[-80.6,29.4],[-80.7,29.5],[-80.7,29.7],[-80.7,29.9],	[-80.7,30.0],[-80.7,30.2],[-80.7,30.3],[-80.6,30.5],[-80.6,30.7]] #Matthew
#track = [[-94.3,25],[-94.4,25.07],[-94.5,25.13],[-94.6,25.2],[-94.8,25.4],[-94.9,25.5],[-95.1,25.6],[-95.2,25.8],[-95.4,25.8],[-95.4,25.9],[-95.5,26.1],[-95.7,26.3],[-95.8,26.3],[-95.9,26.4],[-95.9,26.5],[-96,26.7],[-96.2,26.8],[-96.2,26.9],[-96.3,27.1],[-96.4,27.2],[-96.4,27.4],[-96.5,27.5],[-96.6,27.6],[-96.7,27.7],[-96.8,27.8]] #Harvey
#track = [[-80.8,23.3],[-80.9,23.4],[-81.0,23.5],[-81.0,23.5],[-81.1,23.6],[-81.2,23.7],[-81.3,23.7],[-81.3,23.9],[-81.4,23.9],[-81.5,24.1],[-81.4,24.2],[-81.5,24.4],[-81.5,24.5],[-81.5,24.6],[-81.5,24.8],[-81.5,25.0],[-81.6,25.2],[-81.7,25.4],[-81.8,25.6],[-81.8,25.7],[-81.7,26.0],[-81.8,26.2],[-81.7,26.3],[-81.7,26.6],[-81.7,26.7]] #Irma
track = [[-86.2,25.0],[-86.27,25.13],[-86.33,25.27],[-86.4,25.4],[-86.4,25.6],[-86.4,25.8],[-86.4,26.0],[-86.43,26.2],[-86.46,26.4],[-86.5,26.6],[-86.5,26.77],[-86.5,26.93],[-86.5,27.1],[-86.53,27.3],[-86.57,27.5],[-86.6,27.7],[-86.57,27.9],[-86.53,28.1],[-86.5,28.3],[-86.43,28.6],[-86.37,28.8],[-86.3,29.0],[-86.2,29.1],[-86.1,29.3],[-86.0,29.4],[-85.8,29.6],[-85.7,29.9],[-85.5,30.0],[-85.3,30.4],[-85.2,30.6],[-85.1,30.9],[-84.9,31.1],[-84.7,31.3],[-84.5,31.5]]

trackpos = []
tracksec = 0
#This loop takes the center point and adds/subtracts 0.1 degree to each side to create a rectangle to bound the hurricane center image
for t in track:
	trackpos.append(tracksec)
	trackpos.append(truncate(t[0]-0.05,3))
	trackpos.append(truncate(t[1]-0.05,3))
	trackpos.append(truncate(t[0]+0.05,3))
	trackpos.append(truncate(t[1]+0.05,3))
	tracksec+=3600
storm = "Michael"
stormdt = "20181009"
imergVersion = "V05B" #Matthew is V04A
#bounds = [23,33,-84,-74] #Matthew
#bounds = [22,31,-100,-89] #Harvey
#bounds = [22,29,-86,-77] #Irma
bounds = [23,33,-91,-81] #Michael

def haverarr(lat1,lat2,lon1,lon2):
	R = 6371e3
	phi1 = np.radians(lat1)
	phi2 = np.radians(lat2)
	dphi = np.radians(lat2-lat1)
	dlam = np.radians(lon2-lon1)
	a = np.sin(dphi/2)*np.sin(dphi/2)+np.cos(phi1)*np.cos(phi2)*np.sin(dlam/2)*np.sin(dlam/2)
	c = 2*np.arctan2(np.sqrt(a), np.sqrt(1-a))
	d = R*c
	return d

def haverlazy(lat1,lat2,lon1,lon2):
	latdiff = lat2-lat1
	londiff = lon2-lon1
	return londiff*londiff+latdiff*latdiff

def moveit2(lat,lon,dist,bear):
	R = 6371e3
	raddist = dist/float(R)
	radlat = np.radians(lat)
	radlon = np.radians(lon)

	lat2 = np.arcsin(np.sin(radlat)*np.cos(raddist)+np.cos(radlat)*np.sin(raddist)*np.cos(bear))
	lon2 = radlon+np.arctan2(np.sin(bear)*np.sin(raddist)*np.cos(radlat),np.cos(raddist)-np.sin(radlat)*np.sin(lat2))
	
	return (np.degrees(lat2),np.degrees(lon2))

def colorify(val):
	"""
	This function takes a velocity value, divides it by 5 and floors it to get an index to
	grab an rgb color list to return.
	"""
	clrs = [[77,0,75],[129,15,124],[136,65,157],[140,107,177],[140,150,198],[158,188,218],[191,211,230],[224,236,244],[247,252,253]]
	vscal = int(np.floor((val)/5))
	if vscal>8:
		vscal = 8
	return clrs[vscal]

def colorGMI(lt1,ln1,imlat1,imlon1,precvals):
	"""
	This function takes lat/lon positions and arrays of IMERG lat/lons and precip vals and returns a
	rgb color as a list. It uses haverlazy to find the nearest index point in the IMERG lat/lon grids and
	uses that to find the relevant precip value.
	"""
	clrs = [[0,69,41],[0,104,55],[35,132,67],[65,171,93],[120,198,121],[173,221,142],[217,240,163],[217,240,163],[255,255,229]]
	nearind = np.argmin(haverlazy(lt1,imlat1,ln1,imlon1))
	nearind = np.unravel_index(nearind,imlat1.shape)
	nearprec = precvals[nearind]
	if nearprec<=0:
		return [74,74,74]
	else:
		precscal = int(np.floor(nearprec/5))
		if precscal>8:
			precscal = 8
		return clrs[precscal]

def getImergPrec(fn):
	"""
	This function opens the HDF5 IMERG file based on the filename and outputs vector wind 
	arrays based on the lat/lon bounds.
	"""
	h5f = h5py.File('../Downloads/'+storm+'Motion/IMERG/'+fn,'r')
	h5prec = h5f['Grid']['precipitationCal'][(startlats>=bounds[0]) & (startlats<=bounds[1]) & (startlons>=bounds[2]) & (startlons<=bounds[3])]
	h5f.close()
	return h5prec

def getGribWind(fn,bl):
	"""
	This function opens the grib2 HRRR file based on the filename and outputs vector wind
	arrays based on the lat/lon index array that's passed into it.
	"""
	grbs = pygrib.open('../Downloads/'+storm+'Motion/URMA/'+fn)
	grbs.seek(0)
	grbarr = np.array([])
	grbarr2 = np.array([])
	for grb in grbs:
		if "10 m" in grb.__repr__():
			if "10 metre U wind" in grb.__repr__():
				grbarr = np.array(grb.values)
				grbarr = grbarr[bl]
				grbarr[grbarr>200] = 0
				print "UWind Max: "+str(np.max(grbarr))
			elif "10 metre V wind" in grb.__repr__():
				grbarr2 = np.array(grb.values)
				grbarr2 = grbarr2[bl]
				grbarr2[grbarr2>200] = 0
				print "VWind Max: "+str(np.max(grbarr))
			else:
				continue
		else:
			continue
	grbs.close()
	return (grbarr,grbarr2)

def interpArr(arr1,arr2,t,scal):
	"""
	So, we're taking two arrays, whatever the time is in seconds (t), and whatever the time increment between files is (scal)
	and	interpolating between them, returning the result as a single array.
	"""
	return arr1*float(scal-t%scal)/float(scal)+arr2*float(t%scal)/float(scal)

def interpVelAng(uarr1,uarr2,varr1,varr2,t,scal):
	"""
	Here, I'm taking the vector wind arrays from time1 and time2, and outputting the combined wind speeds and wind directions.
	I do the interpolation on each of the vector arrays, then combine those interpolated values for vels and angs
	"""
	uvels = interpArr(uarr1,uarr2,t,scal)
	vvels = interpArr(varr1,varr2,t,scal)
	vels = np.sqrt(uvels*uvels+vvels*vvels)
	angs = np.arctan2(uvels,vvels)
	return (vels,angs)

#Get file lists and sort them		
h5fns = os.listdir('../Downloads/'+storm+'Motion/IMERG/')	
h5fns.sort()
grbfiles = os.listdir('../Downloads/'+storm+'Motion/URMA/')
grbfiles.sort()

#Grab the GRIB lat/lon arrays and shrink them to domain of interest and get initial velocity/angle arrays
grbs = pygrib.open('../Downloads/'+storm+'Motion/URMA/urma2p5.t15z.2dvaranl_ndfd.grb2')
grbs.seek(0)
lls = []
grbarr = np.array([])
grbarr2 = np.array([])
k = 1
data = {}
inddict = []
for grb in grbs:
	if k is 1:
		lats, lons = grb.latlons()
		lats = np.array(lats)
		lons = np.array(lons)
		bestlats = np.where((lats>=bounds[0]) & (lats<=bounds[1]) & (lons>=bounds[2]) & (lons<=bounds[3]))
		lats = lats[bestlats]
		lons = lons[bestlats]
		smalllats = lats[::64]
		smalllons = lons[::64]
		k+=1
	else:
		k+=1
		continue
grbs.close()
goodlat = smalllats+(np.random.random_sample(smalllats.shape)-.5)*0.1
goodlon = smalllons+(np.random.random_sample(smalllats.shape)-.5)*0.1
ugrib1,vgrib1 = getGribWind(grbfiles[0],bestlats)
ugrib2,vgrib2 = getGribWind(grbfiles[1],bestlats)
goodvel,goodang = interpVelAng(ugrib1,ugrib2,vgrib1,vgrib2,0,3600)
goodvel = goodvel[::64]
goodang = goodang[::64]

#Grab the IMERG lat/lon arrays and shrink them to domain of interest and get initial precipitation arrays
h5f = h5py.File('../Downloads/'+storm+'Motion/IMERG/3B-HHR-E.MS.MRG.3IMERG.'+stormdt+'-S150000-E152959.0900.'+imergVersion+'.RT-H5','r')
h5lats = h5f['Grid']['lat'][:]
h5lons = h5f['Grid']['lon'][:]
startlats,startlons = np.meshgrid(h5lats,h5lons)
h5lats = startlats[(startlats>=bounds[0]) & (startlats<=bounds[1]) & (startlons>=bounds[2]) & (startlons<=bounds[3])]
h5lons = startlons[(startlats>=bounds[0]) & (startlats<=bounds[1]) & (startlons>=bounds[2]) & (startlons<=bounds[3])]
h5f.close()
imprec1 = getImergPrec(h5fns[0])
imprec2 = getImergPrec(h5fns[1])

#Establish the initial lists for the points in the data dictionary
for l in range(len(goodlat)):
	cv = colorify(goodvel[l])
	c = colorGMI(goodlat[l],goodlon[l],h5lats,h5lons,imprec1)
	alp = 255
	if c[0] is 74:
		alp = 0
	data[str(l)] = {'lls':[0,truncate(goodlon[l],3),truncate(goodlat[l],3),0],'cols':[0,c[0],c[1],c[2],alp],'colsV':[0,cv[0],cv[1],cv[2],255]}
	inddict.append(l)
inddict = np.array(inddict)

timer = 0
while timer<1188:
	timer+=1
	timesec = timer*100
	print "Time"
	print timesec
	print len(goodlat)
	#Grabs the new GRIB2 and IMERG data and copies the old data to the first spot
	if timesec%1800 is 0 and timer<1188:
		imprec1 = copy.deepcopy(imprec2)
		imprec2 = getImergPrec(h5fns[int(timesec/1800)+1])
	if timesec%3600 is 0 and timer<1188:
		ugrib1 = copy.deepcopy(ugrib2)
		vgrib1 = copy.deepcopy(vgrib2)
		ugrib2,vgrib2 = getGribWind(grbfiles[int(timesec/3600)+1],bestlats)
	
	#Based on the new time step, this creates the new interpolated velocity, angle, and precipitation arrays
	grbboth,grbang = interpVelAng(ugrib1,ugrib2,vgrib1,vgrib2,timesec,3600)
	if timer%3 is 0:
		goodprec = interpArr(imprec1,imprec2,timesec,1800)
	#Have to handle the final loop which is just the last array uninterpolated
	if timer is 1188:
		grbboth,grbang = interpVelAng(ugrib2,ugrib2,vgrib2,vgrib2,timesec,3600)
		goodprec = interpArr(imprec2,imprec2,timesec,1800)
	
	#Computes the motion of the points and adds the new position and colors to the data dictionary (if timer%3 is 0)
	goodlat,goodlon = moveit2(goodlat,goodlon,goodvel*50,goodang)
	for g in range(len(goodlat)):
		closeind = np.argmin(haverlazy(goodlat[g],lats,goodlon[g],lons))
		goodvel[g] = grbboth[closeind]
		goodang[g] = grbang[closeind]
		if timer%3 is 0:
			c = colorGMI(goodlat[g],goodlon[g],h5lats,h5lons,goodprec)
			cv = colorify(goodvel[g])
			alp = 255
			valp = 255
			if c[0] is 74:
				alp = 0
			if goodlat[g]<bounds[0] or goodlat[g]>bounds[1] or goodlon[g]<bounds[2] or goodlon[g]>bounds[3]:
				alp = 0
				valp = 0
			data[str(inddict[g])]['lls']+=[timesec,truncate(goodlon[g],3),truncate(goodlat[g],3),0]
			data[str(inddict[g])]['cols']+=[timesec,c[0],c[1],c[2],alp]
			data[str(inddict[g])]['colsV']+=[timesec,cv[0],cv[1],cv[2],valp]
	
	#Removes points that are too close to other points (basically less than 0.01 degrees difference)
	diffgrid = haverlazy(goodlat.reshape((len(goodlat),1)),goodlat.reshape((1,len(goodlat))),goodlon.reshape((len(goodlon),1)),goodlon.reshape((1,len(goodlon))))
	maskdiff = ma.masked_array(diffgrid,mask=(diffgrid==0))
	gridmin = np.min(maskdiff,axis=1)
	if np.min(gridmin)<0.0001:
		gridinds = np.where(gridmin<0.0001)
		q = 0
		for d in gridinds[0]:
			z = d-q
			print z
			print gridmin[d]
			if z is 0:
				goodlat = goodlat[1:]
				goodlon = goodlon[1:]
				goodvel = goodvel[1:]
				goodang = goodang[1:]
				inddict = inddict[1:]
			elif z is len(goodlat)-1:
				goodlat = goodlat[:-1]
				goodlon = goodlon[:-1]
				goodvel = goodvel[:-1]
				goodang = goodang[:-1]
				inddict = inddict[:-1]
			else:
				goodlat = np.concatenate((goodlat[:z],goodlat[z+1:]))
				goodlon = np.concatenate((goodlon[:z],goodlon[z+1:]))
				goodvel = np.concatenate((goodvel[:z],goodvel[z+1:]))
				goodang = np.concatenate((goodang[:z],goodang[z+1:]))
				inddict = np.concatenate((inddict[:z],inddict[z+1:]))
			q+=1
	
	#Adds new points from the original grid where there is a space greater than 0.1 degree between the initial grid and any points in motion
	adddiff = haverlazy(smalllats.reshape((len(smalllats),1)),goodlat.reshape((1,len(goodlat))),smalllons.reshape((len(smalllons),1)),goodlon.reshape((1,len(goodlon))))
	eachmin = np.min(adddiff,axis=1)
	if np.max(eachmin)>=0.1:
		addinds = np.where(eachmin>=0.1)
		glen = len(data)
		j = 0
		for k in addinds[0]:
			c = colorGMI(smalllats[k],smalllons[k],h5lats,h5lons,goodprec)
			cv = colorify(grbboth[k])
			if c[0] is 74:
				alp = 0
			data[str(j+glen)] = {'lls':[timesec,truncate(smalllons[k],3),truncate(smalllats[k],3),0],'cols':[timesec,c[0],c[1],c[2],alp],'colsV':[timesec,cv[0],cv[1],cv[2],255]}
			goodlat = np.append(goodlat,smalllats[k])
			goodlon = np.append(goodlon,smalllons[k])
			goodvel = np.append(goodvel,grbboth[k])
			goodang = np.append(goodang,grbang[k])
			inddict = np.append(inddict,j+glen)
			j+=1

# Open the two CZML files (Precip and Velocity) and loop through data, writing out the points and their position/color
outfile = open('./'+storm+'SpiralMotionMod.js','w')		
outfile.write('[{"id":"document","name":"CZML SPIRAL","version":"1.0","clock":{"interval":"2018-10-09T15:00:00Z/2018-10-11T00:00:00Z","currentTime":"2018-10-09T15:00:00Z","multiplier": 1800}}')
#outfileVel = open('./'+storm+'SpiralMotionVel.js','w')
#outfileVel.write('[{"id":"document","name":"CZML SPIRAL","version":"1.0","clock":{"interval":"2017-08-25T00:00:00Z/2017-08-26T00:00:00Z","currentTime":"2017-08-25T00:00:00Z","multiplier": 900}}')
for dat in data:
	point = data[dat]
	outfile.write(',{"id":"'+dat+'p","position":{"epoch":"2018-10-09T15:00:00Z","cartographicDegrees":'+str(point['lls'][:]).replace("'",'')+'},"point":{"color":{"epoch":"2018-10-09T15:00:00Z","rgba":'+str(point['cols'][:])+'},"pixelSize":1.0,"borderWidth":0.0,"scaleByDistance":{"nearFarScalar":[300000,3.5,1200000,2.0]}}}')

	outfile.write(',{"id":"'+dat+'v","position":{"reference":"'+dat+'p#position"},"point":{"color":{"epoch":"2018-10-09T15:00:00Z","rgba":'+str(point['colsV'][:])+'},"pixelSize":1.0,"borderWidth":0.0,"scaleByDistance":{"nearFarScalar":[300000,3.5,1200000,2.0]}}}')

#outfile.write(',{"id":"center","rectangle":{"coordinates":{"epoch":"2018-10-09T15:00:00Z","wsenDegrees":'+str(trackpos).replace("'",'')+'},"height":2000,"fill":true,"material":{"solidColor":{"color":{"rgba": [255,0,0,255]}}}}}')
#outfileVel.write(',{"id":"center","availability":"2017-08-25T00:00:00Z/2017-08-26T00:00:00Z","rectangle":{"coordinates":{"epoch":"2017-08-25T00:00:00Z","wsenDegrees":'+str(trackpos).replace("'",'')+'},"height":2000,"fill":true,"material":{"image":{"image":{"uri":"../../images/centerX.png"},"transparent":true}}}}')

outfile.write(']')
outfile.close()
#outfileVel.write(']')
#outfileVel.close()
